# 03 — Sequence Diagrams

## 3.1 Synchronous Bill Payment Flow (Happy Path + Agent Commission + Audit)

```mermaid
sequenceDiagram
    autonumber
    actor Caller as Customer / Agent
    participant Ctrl as PaymentController
    participant PaySvc as PaymentService
    participant Fraud as FraudCheckChain
    participant Fee as FeeCalculator
    participant WalletRepo as WalletRepository<br/>(PESSIMISTIC_WRITE)
    participant Audit as AuditService
    participant Adapter as BillerAdapter
    participant Events as ApplicationEventPublisher

    Caller ->> Ctrl: POST /api/v1/payments<br/>(Idempotency-Key, billerCode, customerRef, billAmount)
    Ctrl ->> PaySvc: initiatePayment(command, actorId)
    PaySvc ->> PaySvc: Check idempotencyKey in payments table

    alt Idempotency-Key already exists
        PaySvc -->> Ctrl: Return stored original PaymentResponse (200 OK)
    else New Idempotency-Key
        PaySvc ->> Fraud: evaluate(user, billAmount, dailyPaidTotal, recentCount)
        alt Fraud Rule Violated (Blocked / Velocity / Single or Daily Limit)
            Fraud -->> PaySvc: throws FraudRejectedException
            PaySvc ->> PaySvc: Persist Payment(REJECTED) + StateHistory
            PaySvc -->> Ctrl: 422 Unprocessable Entity (Rejection Reason)
        else Fraud Passed
            PaySvc ->> Fee: calculate(billerCode, category, billAmount, isAgent)
            Fee -->> PaySvc: FeeBreakdown(serviceFee, totalAmount, agentCommission)

            Note over PaySvc, Audit: Atomic TX 1 — Lock Wallet, Hold Funds & Persist SENT_TO_BILLER
            PaySvc ->> WalletRepo: findByUserIdForUpdate(payerUserId)
            WalletRepo -->> PaySvc: Locked Wallet
            PaySvc ->> PaySvc: wallet.hold(totalAmount) -> LedgerEntry(HOLD)
            PaySvc ->> PaySvc: payment.transitionTo(FUNDS_HELD -> SENT_TO_BILLER)
            PaySvc ->> Audit: record(actorId, "FUNDS_HELD", paymentRef)

            PaySvc ->> Adapter: pay(BillerPaymentRequest) [10s timeout]

            alt Biller Confirms Success
                Note over PaySvc, Events: Atomic TX 2 — Capture Hold, Credit Commission & Publish Event
                PaySvc ->> WalletRepo: findByUserIdForUpdate(payerUserId)
                PaySvc ->> PaySvc: wallet.captureHold(totalAmount) -> LedgerEntry(CAPTURE)
                opt isAgent && agentCommission > 0
                    PaySvc ->> WalletRepo: findByUserIdForUpdate(agentUserId)
                    PaySvc ->> PaySvc: agentWallet.credit(agentCommission) -> LedgerEntry(AGENT_COMMISSION)
                end
                PaySvc ->> PaySvc: payment.transitionTo(COMPLETED)
                PaySvc ->> Audit: record(actorId, "PAYMENT_COMPLETED", paymentRef)
                PaySvc ->> Events: publishEvent(PaymentCompletedEvent)
                PaySvc -->> Ctrl: 201 Created (PaymentResponse: COMPLETED)
            else Biller Refuses (4xx / Business Error)
                Note over PaySvc, Events: Atomic TX 2b — Release Held Funds & Notify Failure
                PaySvc ->> WalletRepo: findByUserIdForUpdate(payerUserId)
                PaySvc ->> PaySvc: wallet.releaseHold(totalAmount) -> LedgerEntry(RELEASE)
                PaySvc ->> PaySvc: payment.transitionTo(FAILED -> REFUNDED)
                PaySvc ->> Audit: record(actorId, "PAYMENT_FAILED_REFUNDED", paymentRef)
                PaySvc ->> Events: publishEvent(PaymentFailedEvent)
                PaySvc -->> Ctrl: 200 OK / 400 (PaymentResponse: REFUNDED)
            else Biller Timeout (> 10s)
                PaySvc ->> PaySvc: payment.transitionTo(UNKNOWN), schedule retry (+1m)
                PaySvc ->> Audit: record(actorId, "PAYMENT_UNKNOWN_TIMEOUT", paymentRef)
                PaySvc -->> Ctrl: 202 Accepted (PaymentResponse: UNKNOWN)
            end
        end
    end
```

---

## 3.2 Asynchronous Webhook Callback & Timeout Re-Query Flow (TopUpNet / `UNKNOWN`)

```mermaid
sequenceDiagram
    autonumber
    participant Biller as TopUpNet / External Biller
    participant Webhook as WebhookController
    participant Sched as UnknownPaymentRetryJob<br/>(1m, 5m, 15m)
    participant PaySvc as PaymentService
    participant WalletRepo as WalletRepository
    participant Audit as AuditService

    alt Asynchronous Webhook Arrives
        Biller ->> Webhook: POST /api/v1/webhooks/billers/topupnet<br/>(paymentReference, status, billerTxId)
        Webhook ->> PaySvc: handleCallback(callbackPayload)
        PaySvc ->> PaySvc: Lock Payment row (PESSIMISTIC_WRITE)
        alt Payment already COMPLETED or REFUNDED (Duplicate Callback)
            PaySvc ->> Audit: record(SYSTEM, "DUPLICATE_CALLBACK_IGNORED", paymentRef)
            PaySvc -->> Webhook: 200 OK (Idempotent Ack)
        else Payment in SENT_TO_BILLER or UNKNOWN
            PaySvc ->> WalletRepo: findByUserIdForUpdate(payerUserId)
            alt status == "SUCCESS"
                PaySvc ->> PaySvc: wallet.captureHold(totalAmount) + LedgerEntry(CAPTURE)
                PaySvc ->> PaySvc: payment.transitionTo(COMPLETED)
                PaySvc ->> Audit: record(SYSTEM, "CALLBACK_COMPLETED", paymentRef)
            else status == "FAILED"
                PaySvc ->> PaySvc: wallet.releaseHold(totalAmount) + LedgerEntry(RELEASE)
                PaySvc ->> PaySvc: payment.transitionTo(FAILED -> REFUNDED)
                PaySvc ->> Audit: record(SYSTEM, "CALLBACK_FAILED_REFUNDED", paymentRef)
            end
            PaySvc -->> Webhook: 200 OK
        end
    else Scheduled Re-Query for UNKNOWN Payment
        Sched ->> PaySvc: requeryUnknownPayments()
        PaySvc ->> Biller: adapter.checkStatus(paymentReference)
        alt Biller Reports Confirmed
            PaySvc ->> PaySvc: Capture hold -> COMPLETED + Audit
        else Biller Reports Not Found / Failed
            PaySvc ->> PaySvc: Release hold -> FAILED -> REFUNDED + Audit
        else Still Pending / Timeout & retryCount < 3
            PaySvc ->> PaySvc: Increment retryCount, schedule next (+5m / +15m)
        end
    end
```

---

## 3.3 Refund a Failed Payment Flow (Automatic Hold Release & Support Refund)

```mermaid
sequenceDiagram
    autonumber
    actor Caller as Customer / Support / Biller
    participant Ctrl as PaymentController / SupportController
    participant PaySvc as PaymentService
    participant WalletRepo as WalletRepository<br/>(PESSIMISTIC_WRITE)
    participant Audit as AuditService
    participant Events as ApplicationEventPublisher
    participant Notif as NotificationConsumer

    alt Scenario A: Automatic Hold Release on Biller Refusal (FR-12 / Hard Case #3)
        Note over Caller, Notif: Biller explicitly refuses payment (e.g., invalid meter, AquaCo overpayment)
        Caller ->> Ctrl: POST /api/v1/payments (execute payment)
        Ctrl ->> PaySvc: processPayment(cmd)
        Note over PaySvc: Funds already in FUNDS_HELD, state = SENT_TO_BILLER
        PaySvc ->> PaySvc: billerAdapter.pay() returns BillerRefusedException("INVALID_METER")

        Note over PaySvc, Audit: Atomic TX — Release Hold, Transition FAILED -> REFUNDED
        PaySvc ->> WalletRepo: findByUserIdForUpdate(payerUserId)
        WalletRepo -->> PaySvc: Locked Wallet
        PaySvc ->> PaySvc: wallet.releaseHold(totalAmount)
        PaySvc ->> PaySvc: Append LedgerEntry(RELEASE, amount=totalAmount, heldAfter=0)
        PaySvc ->> PaySvc: payment.transitionTo(FAILED, "Biller refused: INVALID_METER")
        PaySvc ->> PaySvc: payment.transitionTo(REFUNDED, "Hold released to wallet balance")
        PaySvc ->> Audit: record(SYSTEM, "PAYMENT_REFUNDED_HOLD_RELEASED", paymentRef)
        PaySvc ->> Events: publishEvent(PaymentFailedEvent(paymentRef, reason))

        Events ->> Notif: onPaymentFailed(event)
        Notif ->> Notif: Dispatch SMS & Push notification with failure reason
        PaySvc -->> Ctrl: 400 Bad Request / PaymentResponse(status=REFUNDED, reason="INVALID_METER")
        Ctrl -->> Caller: PaymentResponse (funds restored to availableBalance)

    else Scenario B: Support-Approved Post-Completion Refund (FR-27)
        Note over Caller, Notif: Support reviews customer claim and approves refund for COMPLETED payment
        Caller ->> Ctrl: POST /api/v1/support/payments/{paymentRef}/refund<br/>(reason, supportActorId)
        Ctrl ->> PaySvc: approveRefund(paymentRef, supportActorId, reason)
        PaySvc ->> PaySvc: Lock Payment (status must be COMPLETED)

        Note over PaySvc, Audit: Atomic TX — Compensating Wallet Credit & State Transition
        PaySvc ->> WalletRepo: findByUserIdForUpdate(payerUserId)
        WalletRepo -->> PaySvc: Locked Customer Wallet
        PaySvc ->> PaySvc: customerWallet.credit(totalAmount, REFUND)
        PaySvc ->> PaySvc: Append LedgerEntry(REFUND, amount=totalAmount, availableAfter+=totalAmount)
        opt Agent was involved and earned commission (FR-17)
            PaySvc ->> WalletRepo: findByUserIdForUpdate(agentUserId)
            WalletRepo -->> PaySvc: Locked Agent Wallet
            PaySvc ->> PaySvc: agentWallet.debit(commissionAmount, COMMISSION_REVERSAL)
            PaySvc ->> PaySvc: Append LedgerEntry(COMMISSION_REVERSAL, amount=commissionAmount)
        end
        PaySvc ->> PaySvc: payment.transitionTo(REFUNDED, reason)
        PaySvc ->> Audit: record(supportActorId, "SUPPORT_REFUND_APPROVED", paymentRef)
        PaySvc ->> Events: publishEvent(PaymentRefundedEvent(paymentRef))

        Events ->> Notif: onPaymentRefunded(event)
        Notif ->> Notif: Dispatch refund receipt SMS & Push notification
        PaySvc -->> Ctrl: 200 OK (RefundConfirmationResponse)
        Ctrl -->> Caller: 200 OK (Refund Completed)
    end
```
