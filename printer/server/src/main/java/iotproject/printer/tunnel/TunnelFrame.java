package iotproject.printer.tunnel;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.Set;

/** v1 framing: bounded before allocation; TCP read boundaries are irrelevant. */
public record TunnelFrame(int type, int streamId, byte[] payload) {
    public static final int AUTH=1, AUTH_OK=2, AUTH_ERROR=3, OPEN=16, OPEN_OK=17, OPEN_ERROR=18,
            DATA=32, WINDOW=33, FIN=34, CLOSED=35, RESET=36, PING=48, PONG=49;
    public static final int WINDOW_SIZE=4096, CHUNK=1024;
    private static final Set<Integer> TYPES=Set.of(1,2,3,16,17,18,32,33,34,35,36,48,49);
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public TunnelFrame {
        if (!TYPES.contains(type) || payload.length > (type==DATA ? CHUNK : 512)
                || (type==DATA && payload.length==0)
                || ((type==FIN || type==CLOSED) && payload.length!=0)
                || ((type<=3 || type>=48) != (streamId==0))) {
            throw new IllegalArgumentException("Invalid tunnel frame");
        }
    }
    public static TunnelFrame read(InputStream input) throws IOException {
        DataInputStream in=new DataInputStream(input);
        if(in.readUnsignedShort()!=0x4954 || in.readUnsignedByte()!=1) throw new IOException("Invalid protocol");
        int type=in.readUnsignedByte(), id=in.readInt(), length=in.readInt(), reserved=in.readInt();
        if(reserved!=0 || length<0 || length>(type==DATA ? CHUNK : 512)) throw new IOException("Invalid frame length/header");
        byte[] body=new byte[length]; in.readFully(body);
        try { return new TunnelFrame(type,id,body); }
        catch(IllegalArgumentException e) { throw new IOException("Invalid frame",e); }
    }
    public void write(OutputStream output) throws IOException {
        DataOutputStream out=new DataOutputStream(output);
        out.writeShort(0x4954); out.writeByte(1); out.writeByte(type); out.writeInt(streamId);
        out.writeInt(payload.length); out.writeInt(0); out.write(payload); out.flush();
    }
    public static TunnelFrame json(int type,int id,Object value) throws IOException {
        return new TunnelFrame(type,id,JSON.writeValueAsBytes(value));
    }
    public JsonNode json() throws IOException {
        JsonNode node=JSON.readTree(payload);
        if(node==null || !node.isObject()) throw new IOException("Expected JSON object");
        // Reject trailing JSON, not just the first parsed object.
        try(var parser=JSON.getFactory().createParser(payload)) {
            JSON.readTree(parser); if(parser.nextToken()!=null) throw new IOException("Trailing JSON");
        }
        return node;
    }
    static int number(JsonNode json,String name,int min,int max) throws IOException {
        JsonNode v=json.get(name);
        if(v==null || !v.isIntegralNumber() || !v.canConvertToInt() || v.intValue()<min || v.intValue()>max)
            throw new IOException("Invalid field: "+name);
        return v.intValue();
    }
    static String text(JsonNode json,String name,int max) throws IOException {
        JsonNode v=json.get(name);
        if(v==null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length()>max)
            throw new IOException("Invalid field: "+name);
        return v.textValue();
    }
}
