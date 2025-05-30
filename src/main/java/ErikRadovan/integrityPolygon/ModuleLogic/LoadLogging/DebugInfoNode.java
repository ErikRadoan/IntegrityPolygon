package ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging;

import java.util.ArrayList;
import java.util.List;

public class DebugInfoNode {
    public enum LogLevel {
        SUCCESS("\uD83D\uDFE9"),
        FAILURE("\uD83D\uDFE5"),
        PARTIAL("\uD83D\uDFE6");

        private final String icon;

        LogLevel(String icon) {
            this.icon = icon;
        }

        public String getIcon() {
            return icon;
        }
    }

    private final String name;
    private final LogLevel level;
    private final List<DebugInfoNode> children = new ArrayList<>();
    private String exceptionMessage;

    public DebugInfoNode(String name, LogLevel level) {
        this.name = name;
        this.level = level;
    }

    public DebugInfoNode addChild(String name, LogLevel level) {
        DebugInfoNode child = new DebugInfoNode(name, level);
        children.add(child);
        return child;
    }

    public void setException(Throwable ex) {
        this.exceptionMessage = ex.toString();
    }

    public boolean hasExceptionDeep() {
        if (exceptionMessage != null) return true;
        for (DebugInfoNode child : children) {
            if (child.hasExceptionDeep()) return true;
        }
        return false;
    }

    public void print(String prefix, boolean isLast) {
        String branch = prefix + (isLast ? "╰┈" : "├┈");
        System.out.println(branch + "➤ " + level.getIcon() + " " + name);
        if (exceptionMessage != null) {
            String indent = prefix + (isLast ? "   " : "│  ");
            System.out.println(indent + "     ╰┈┈┈┈┈➤ " + exceptionMessage);
        }

        int printed = 0;
        for (DebugInfoNode child : children) {
            if (!child.hasExceptionDeep()) continue; // skip irrelevant
            boolean last = ++printed == (int) children.stream().filter(DebugInfoNode::hasExceptionDeep).count();
            child.print(prefix + (isLast ? "   " : "│  "), last);
        }
    }

    public String getIcon() {
        return level.getIcon();
    }

    public String getName() {
        return name;
    }
}
