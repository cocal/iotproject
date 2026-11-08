package iotproject.printer.legacy;

import iotproject.printer.jobs.PrintJobRepository;
import iotproject.printer.render.EscPosReceiptRenderer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacySourceIpTest {
    @Test void persistsActualTcpPeerForBothTerminationModes() throws Exception {
        for(boolean addressLine: List.of(true,false)) {
            var jobs=mock(PrintJobRepository.class);
            UUID id=UUID.randomUUID();
            when(jobs.createLegacyJob(anyString(),anyMap(),anyString())).thenReturn(id);
            var receiver=new LegacyTcpReceiver(jobs,new LegacyInvoiceParser(),new EscPosReceiptRenderer(),0,"127.0.0.1");
            try(var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
                var sender=new Socket("127.0.0.1",listener.getLocalPort());var accepted=listener.accept()) {
                var method=LegacyTcpReceiver.class.getDeclaredMethod("handle",Socket.class);method.setAccessible(true);
                var handling=CompletableFuture.runAsync(()->{
                    try {method.invoke(receiver,accepted);}catch(Exception e){throw new CompletionException(e);}
                });
                String raw="往来单位:测试客户\n品名 数量\n测试商品 1\n合计金额:0\n"+(addressLine?"地址:测试\n":"");
                sender.getOutputStream().write(raw.getBytes(Charset.forName("GBK")));sender.shutdownOutput();
                handling.get(3,TimeUnit.SECONDS);
                verify(jobs).createLegacyJob(eq(raw.stripTrailing()),argThat(data->data.get("customer").equals("测试客户")),eq("127.0.0.1"));
                verify(jobs).markRendered(eq(id),any(byte[].class),anyString());
            } finally {receiver.stop();}
        }
    }
}
