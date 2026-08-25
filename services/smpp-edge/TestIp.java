import com.cloudhopper.smpp.SmppSession;
import java.lang.reflect.Method;
public class TestIp {
    public static void main(String[] args) throws Exception {
        for (Method m : SmppSession.class.getMethods()) {
            System.out.println(m.getName());
        }
    }
}
