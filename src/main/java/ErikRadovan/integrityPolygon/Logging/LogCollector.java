package ErikRadovan.integrityPolygon.Logging;

import ErikRadovan.integrityPolygon.Panel.CommunicationModule;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class LogCollector {
    private final List<LogEvent> logs = Collections.synchronizedList(new ArrayList<>());
    private static String user_id = "";

    CommunicationModule communicationModule;

    public LogCollector(CommunicationModule communicationModule) {
        this.communicationModule = communicationModule;
    }

    public void log(String moduleName, String tag, String message) {
        LogEvent logEvent = new LogEvent(moduleName, tag, message);
        logs.add(logEvent);

        communicationModule.SendLog(logEvent);
    }

    public void log(String moduleName, String tag, String message, JSONObject context) {
        LogEvent logEvent = new LogEvent(moduleName, tag, message);
        logs.add(logEvent);

        communicationModule.SendLog(logEvent);
    }

    private List<LogEvent> getLogs(String tagFilter) {
        return logs.stream()
                .filter(e -> tagFilter == null || e.getTag().equalsIgnoreCase(tagFilter))
                .collect(Collectors.toList());
    }
}
