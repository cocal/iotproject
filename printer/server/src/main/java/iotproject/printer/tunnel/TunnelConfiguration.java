package iotproject.printer.tunnel;

import iotproject.printer.device.DeviceAuthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.net.InetSocketAddress;
import java.util.*;

@Configuration
@ConditionalOnProperty(name="printer.tunnel.enabled",havingValue="true")
@EnableConfigurationProperties(TunnelConfiguration.Settings.class)
public class TunnelConfiguration {
    @ConfigurationProperties("printer.tunnel")
    public static class Settings {
        private String bind="0.0.0.0",keyStore,keyStorePassword;
        private int port=9443,maxConnections=64;
        private List<TunnelGateway.Mapping> mappings=new ArrayList<>();
        public String getBind(){return bind;} public void setBind(String v){bind=v;}
        public String getKeyStore(){return keyStore;} public void setKeyStore(String v){keyStore=v;}
        public String getKeyStorePassword(){return keyStorePassword;} public void setKeyStorePassword(String v){keyStorePassword=v;}
        public int getPort(){return port;} public void setPort(int v){port=v;}
        public int getMaxConnections(){return maxConnections;} public void setMaxConnections(int v){maxConnections=v;}
        public List<TunnelGateway.Mapping> getMappings(){return mappings;} public void setMappings(List<TunnelGateway.Mapping> v){mappings=v;}
    }
    @Bean(initMethod="start",destroyMethod="close")
    TunnelGateway tunnelGateway(Settings settings,DeviceAuthService auth) throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");
        char[] password=Objects.requireNonNull(settings.getKeyStorePassword(),"TLS keystore password required").toCharArray();
        try(var input=Files.newInputStream(Path.of(Objects.requireNonNull(settings.getKeyStore(),"TLS keystore required")))) {
            store.load(input,password);
        }
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password);
        Arrays.fill(password,'\0');
        SSLContext context=SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(),null,null);
        return new TunnelGateway(context,new InetSocketAddress(settings.getBind(),settings.getPort()),
                auth::authenticate,settings.getMappings(),settings.getMaxConnections());
    }
}
