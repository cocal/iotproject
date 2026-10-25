package iotproject.printer.legacy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LegacyReceiverConfiguration {
    @Bean
    LegacyInvoiceParser legacyInvoiceParser() {
        return new LegacyInvoiceParser();
    }
}
