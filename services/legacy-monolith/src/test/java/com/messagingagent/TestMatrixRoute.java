package com.messagingagent;

import com.messagingagent.routing.MatrixRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;

@SpringBootTest
public class TestMatrixRoute {

    @Autowired
    private MatrixRouteService matrixRouteService;

    @Test
    public void testDirectResolve() throws Exception {
        Method method = MatrixRouteService.class.getDeclaredMethod("resolvePortalRoomIdDirect", String.class);
        method.setAccessible(true);
        String roomId = (String) method.invoke(matrixRouteService, "306973699061");
        System.out.println("RESOLVED ROOM ID: " + roomId);
        
        assert roomId != null;
        assert roomId.startsWith("!");
    }
}
