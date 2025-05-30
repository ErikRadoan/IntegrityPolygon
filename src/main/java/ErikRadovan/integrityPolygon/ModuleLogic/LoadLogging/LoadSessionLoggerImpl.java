package ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging;

import java.util.ArrayList;
import java.util.List;

public class LoadSessionLoggerImpl implements LoadSessionLogger {
    private final List<DebugInfoNode> categories = new ArrayList<>();

    @Override
    public DebugInfoNode createCategory(String category) {
        DebugInfoNode node = new DebugInfoNode(category, DebugInfoNode.LogLevel.SUCCESS);
        categories.add(node);
        return node;
    }

    @Override
    public void printAll() {
        for (int i = 0; i < categories.size(); i++) {
            DebugInfoNode node = categories.get(i);
            boolean isLast = (i == categories.size() - 1);
            if (node.hasExceptionDeep()) {
                System.out.println("➤  ❌  " + node.getIcon() + " " + node.getName() + "   failed to load");
                node.print("    ", isLast);
            } else {
                System.out.println("➤  ✅  " + node.getIcon() + " " + node.getName() + "   loaded successfully");
            }
        }
    }
}
