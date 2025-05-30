package ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging;

public interface LoadSessionLogger {
    DebugInfoNode createCategory(String category);
    void printAll();
}
