package dev.erikradovan.integritypolygon.web;

import com.google.gson.Gson;
import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * Javalin JSON mapper backed by Gson to avoid requiring Jackson at runtime.
 */
public final class GsonJsonMapper implements JsonMapper {

    private final Gson gson = new Gson();

    @Override
    public String toJsonString(Object obj, Type type) {
        return gson.toJson(obj, type);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return gson.fromJson(json, targetType);
    }
}

