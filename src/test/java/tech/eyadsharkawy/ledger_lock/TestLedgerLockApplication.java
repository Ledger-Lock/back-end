package tech.eyadsharkawy.ledger_lock;

import org.springframework.boot.SpringApplication;

public class TestLedgerLockApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgerLockApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
