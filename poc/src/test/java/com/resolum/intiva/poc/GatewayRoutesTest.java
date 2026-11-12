package com.resolum.intiva.poc;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.client.RestClient;

class GatewayRoutesTest {
    @Test
    void routesNewOperationsAndKeepsLegacyRoutesWorking() throws Exception {
        var legacy=start("legacy");
        var agent=start("agent");
        var comm=start("communications");
        try (var context=new SpringApplicationBuilder(PocApplication.class).profiles("gateway")
                .run("--server.port=0","--server.address=127.0.0.1","--intiva.legacy="+url(legacy),
                    "--intiva.agent="+url(agent),"--intiva.communications="+url(comm))) {
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var client=RestClient.builder().baseUrl("http://127.0.0.1:"+port).build();
            for (var pair: new String[][]{
                {"/api/v1/monthly-plans","agent"},
                {"/api/v1/monthly-plans/id/approve","agent"},
                {"/api/v1/category-suggestions","agent"},
                {"/api/v1/plan-notifications","communications"},
                {"/api/v1/authentication/sign-in","legacy"}}) {
                var body=client.post().uri(pair[0]).header("Authorization","Bearer test").retrieve().body(String.class);
                assertEquals(pair[1]+":"+pair[0]+":Bearer test",body);
            }
            assertThrows(org.springframework.web.client.HttpClientErrorException.NotFound.class,
                ()->client.get().uri("/internal/plan-limits").retrieve().toBodilessEntity());
        } finally {legacy.stop(0);agent.stop(0);comm.stop(0);}
    }
    private static String url(HttpServer s){return "http://127.0.0.1:"+s.getAddress().getPort();}
    private static HttpServer start(String name) throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{
            var payload=(name+":"+ex.getRequestURI().getPath()+":"+ex.getRequestHeaders().getFirst("Authorization"))
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200,payload.length);ex.getResponseBody().write(payload);ex.close();
        });
        server.start();return server;
    }
}
