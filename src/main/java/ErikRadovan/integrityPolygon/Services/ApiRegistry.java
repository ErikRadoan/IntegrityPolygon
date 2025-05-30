package ErikRadovan.integrityPolygon.Services;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ApiRegistry {
    private final Map<Class<?>, Object> apiMap = new HashMap<>();

    public <T> void registerApi(Class<T> apiClass, T impl) {
        apiMap.put(apiClass, impl);
    }

    public <T> void unregisterApi(Class<T> apiClass) {
        apiMap.remove(apiClass);
    }

    public <T> Optional<T> getApi(Class<T> clazz) {
        Object impl = apiMap.get(clazz);
        if (impl != null) {
            return Optional.of(clazz.cast(impl));
        }
        return Optional.empty();
    }
}
