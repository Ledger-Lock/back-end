# 02 — Domain & Architecture Class Diagram

```mermaid
classDiagram
    direction TB

    %% ================= ACCOUNTS & LEDGER =================
    class User {
        +UUID id
        +String email
        +String phoneNumber
        +String passwordHash
        +Role role
        +VerificationLevel verificationLevel
        +boolean manuallyBlocked
        +Instant velocityBlockedUntil
        +isBlocked(Instant now) boolean
        +getDailyLimit() BigDecimal
    }

    class Wallet {
        +UUID id
        +UUID userId
        +String currency
        +BigDecimal availableBalance
        +BigDecimal heldBalance
        +Long version
        +hold(BigDecimal amount, String ref) LedgerEntry
        +captureHold(BigDecimal amount, String ref) LedgerEntry
        +releaseHold(BigDecimal amount, String ref) LedgerEntry
        +credit(BigDecimal amount, LedgerEntryType type, String ref) LedgerEntry
    }

    class LedgerEntry {
        +UUID id
        +UUID walletId
        +String paymentReference
        +LedgerEntryType entryType
        +BigDecimal amount
        +BigDecimal availableAfter
        +BigDecimal heldAfter
        +Instant createdAt
    }

    %% ================= CATALOG =================
    class Biller {
        +UUID id
        +String code
        +String name
        +BillerProtocol protocol
        +boolean active
    }

    class BillerService {
        +UUID id
        +UUID billerId
        +String serviceCode
        +ServiceCategory category
        +BigDecimal minAmount
        +BigDecimal maxAmount
        +String inputSchemaJson
        +boolean active
    }

    %% ================= PAYMENT & STATE MACHINE =================
    class Payment {
        +UUID id
        +String paymentReference
        +String idempotencyKey
        +UUID payerUserId
        +UUID initiatedByUserId
        +String billerCode
        +String serviceCode
        +String customerIdentifier
        +BigDecimal billAmount
        +BigDecimal serviceFee
        +BigDecimal totalAmount
        +BigDecimal agentCommission
        +PaymentStatus status
        +String billerTransactionId
        +int retryCount
        +Instant nextRetryAt
        +transitionTo(PaymentStatus target, String reason) PaymentStateHistory
    }

    class PaymentStatus {
        <<enumeration>>
        INITIATED
        FUNDS_HELD
        REJECTED
        SENT_TO_BILLER
        COMPLETED
        FAILED
        UNKNOWN
        REFUNDED
        +canTransitionTo(PaymentStatus target) boolean
    }

    class PaymentStateHistory {
        +UUID id
        +UUID paymentId
        +PaymentStatus fromStatus
        +PaymentStatus toStatus
        +String reason
        +Instant transitionedAt
    }

    %% ================= FRAUD (CHAIN OF RESPONSIBILITY) =================
    class FraudCheckChain {
        -List~FraudRule~ rules
        +evaluate(PaymentContext ctx) void
    }

    class FraudRule {
        <<interface>>
        +validate(PaymentContext ctx) void
    }

    class AccountStatusRule {
        +validate(PaymentContext ctx) void
    }

    class VelocityAbuseRule {
        -int maxAttemptsPerMinute
        -Duration blockDuration
        +validate(PaymentContext ctx) void
    }

    class SingleTransactionLimitRule {
        -BigDecimal minSingleAmount
        -BigDecimal maxSingleAmount
        +validate(PaymentContext ctx) void
    }

    class DailyLimitRule {
        -BigDecimal basicDailyLimit
        -BigDecimal verifiedDailyLimit
        +validate(PaymentContext ctx) void
    }

    %% ================= FEES (STRATEGY) =================
    class FeeCalculator {
        -List~FeeStrategy~ strategies
        -PromotionRepository promotionRepo
        -BigDecimal agentCommissionRate
        +calculate(String billerCode, ServiceCategory cat, BigDecimal billAmount, boolean isAgent) FeeBreakdown
    }

    class FeeStrategy {
        <<interface>>
        +supports(ServiceCategory category) boolean
        +computeFee(BigDecimal billAmount) BigDecimal
    }

    class FixedFeeStrategy {
        -BigDecimal fixedFee
        +supports(ServiceCategory category) boolean
        +computeFee(BigDecimal billAmount) BigDecimal
    }

    class PercentageWithMinFeeStrategy {
        -BigDecimal percentageRate
        -BigDecimal minimumFee
        +supports(ServiceCategory category) boolean
        +computeFee(BigDecimal billAmount) BigDecimal
    }

    class TieredInternetFeeStrategy {
        -BigDecimal tierThreshold
        -BigDecimal lowTierFee
        -BigDecimal highTierFee
        +supports(ServiceCategory category) boolean
        +computeFee(BigDecimal billAmount) BigDecimal
    }

    %% ================= INTEGRATION (ADAPTER) =================
    class BillerAdapterRegistry {
        -Map~String, BillerAdapter~ adapters
        +getAdapter(String billerCode) BillerAdapter
    }

    class BillerAdapter {
        <<interface>>
        +getBillerCode() String
        +inquire(InquiryRequest req) InquiryResponse
        +pay(BillerPaymentRequest req) BillerPaymentResponse
        +checkStatus(String paymentReference) BillerPaymentResponse
    }

    class ElectroCoRestAdapter {
        -RestClient restClient
        +getBillerCode() String
        +inquire(InquiryRequest req) InquiryResponse
        +pay(BillerPaymentRequest req) BillerPaymentResponse
        +checkStatus(String paymentReference) BillerPaymentResponse
    }

    class AquaCoSoapAdapter {
        -WebServiceTemplate soapClient
        +getBillerCode() String
        +inquire(InquiryRequest req) InquiryResponse
        +pay(BillerPaymentRequest req) BillerPaymentResponse
        +checkStatus(String paymentReference) BillerPaymentResponse
    }

    class TopUpNetAsyncAdapter {
        -RestClient restClient
        -String callbackUrl
        +getBillerCode() String
        +inquire(InquiryRequest req) InquiryResponse
        +pay(BillerPaymentRequest req) BillerPaymentResponse
        +checkStatus(String paymentReference) BillerPaymentResponse
    }

    class NetCoRestAdapter {
        -RestClient restClient
        +getBillerCode() String
        +inquire(InquiryRequest req) InquiryResponse
        +pay(BillerPaymentRequest req) BillerPaymentResponse
        +checkStatus(String paymentReference) BillerPaymentResponse
    }

    %% ================= AUDIT, NOTIFICATIONS & RECONCILIATION =================
    class AuditLog {
        +UUID id
        +UUID actorId
        +String action
        +String entityType
        +String entityId
        +String reason
        +Instant createdAt
    }

    class Notification {
        +UUID id
        +UUID userId
        +String paymentReference
        +NotificationChannel channel
        +NotificationStatus status
        +int attemptCount
        +markSent() void
        +recordFailure(int maxRetries) void
    }

    class ReconciliationReport {
        +UUID id
        +UUID billerId
        +LocalDate settlementDate
        +int matchedCount
        +int discrepancyCount
        +String discrepanciesJson
        +Instant createdAt
    }

    %% Relationships
    User "1" --> "1" Wallet : owns
    Wallet "1" --> "*" LedgerEntry : appends
    User "1" --> "*" Payment : initiates
    Biller "1" --> "*" BillerService : offers
    BillerService "1" --> "*" Payment : paid under
    Payment "1" --> "1" PaymentStatus : current
    Payment "1" --> "*" PaymentStateHistory : records
    Payment "1" --> "*" Notification : triggers
    User "1" --> "*" AuditLog : performs
    Biller "1" --> "*" ReconciliationReport : reconciled in

    FraudCheckChain o--> "*" FraudRule
    FraudRule <|.. AccountStatusRule
    FraudRule <|.. VelocityAbuseRule
    FraudRule <|.. SingleTransactionLimitRule
    FraudRule <|.. DailyLimitRule

    FeeCalculator o--> "*" FeeStrategy
    FeeStrategy <|.. FixedFeeStrategy
    FeeStrategy <|.. PercentageWithMinFeeStrategy
    FeeStrategy <|.. TieredInternetFeeStrategy

    BillerAdapterRegistry o--> "*" BillerAdapter
    BillerAdapter <|.. ElectroCoRestAdapter
    BillerAdapter <|.. AquaCoSoapAdapter
    BillerAdapter <|.. TopUpNetAsyncAdapter
    BillerAdapter <|.. NetCoRestAdapter