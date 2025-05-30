package ErikRadovan.integrityPolygon.Logging;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class LogEvent {
    private final String module_name;
    private final String tag;
    private final String message;
    private final String created_at;

    public LogEvent(String moduleName, String tag, String message) {
        this.module_name = moduleName;
        this.tag = tag;
        this.message = message;
        Instant now = Instant.now();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX")
                .withZone(ZoneOffset.UTC);

        this.created_at = formatter.format(now);
    }

    public String getModule_name() {
        return module_name;
    }

    public String getTag() {
        return tag;
    }

    public String getMessage() {
        return message;
    }

    public String getCreated_at() {
        return created_at;
    }
}
