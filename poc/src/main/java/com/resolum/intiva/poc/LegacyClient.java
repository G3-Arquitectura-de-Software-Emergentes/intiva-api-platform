package com.resolum.intiva.poc;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile({"agent","communications"})
class LegacyClient {
    private final RestClient client;
    LegacyClient(@Value("${intiva.legacy}") String url) { client = client(url); }
    static RestClient client(String url) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3)); f.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().baseUrl(url).requestFactory(f).build();
    }
    long owner(String bearer) {
        var me = get("/api/v1/users/me", bearer);
        long id = me.path("id").asLong();
        if (id <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return id;
    }
    JsonNode get(String path, String bearer, Object... args) {
        try {
            var body = client.get().uri(path,args).header("Authorization",bearer).retrieve().body(JsonNode.class);
            if (body == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"No data returned by legacy API");
            return body;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Legacy API rejected the request");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Legacy API unavailable");
        }
    }
    record Category(long id, String label) {}
    List<Category> categories(long owner,String bearer) {
        var response=get("/api/v1/categories?ownerId={id}&ownerType=INDIVIDUAL&type=EXPENSE",bearer,owner);
        var result=new ArrayList<Category>();
        for(var n:response) if(n.path("ownerId").asLong()==owner && n.path("isActive").asBoolean()
            && n.path("id").asLong()>0) result.add(new Category(n.path("id").asLong(),n.path("name").asText()));
        return result;
    }
}
