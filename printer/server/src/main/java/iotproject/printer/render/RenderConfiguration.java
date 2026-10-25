package iotproject.printer.render;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RenderConfiguration {
    @Bean
    EscPosReceiptRenderer escPosReceiptRenderer() {
        return new EscPosReceiptRenderer();
    }
}
