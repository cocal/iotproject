package iotproject.printer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.security.Security;

@SpringBootApplication
public class PrinterServerApplication {
    public static void main(String[] args) {
        if (Boolean.parseBoolean(System.getenv("TUNNEL_LEGACY_TLS10_ENABLED"))) {
            String disabled=Security.getProperty("jdk.tls.disabledAlgorithms");
            if(disabled!=null) {
                disabled=disabled.replaceAll("(?i)(^|[,\\s])TLSv1(?=([,\\s]|$))","$1");
                disabled=disabled.replaceAll("(?i)(^|[,\\s])TLS_RSA_\\*(?=([,\\s]|$))","$1");
                Security.setProperty("jdk.tls.disabledAlgorithms",disabled);
            }
        }
        SpringApplication.run(PrinterServerApplication.class, args);
    }
}
