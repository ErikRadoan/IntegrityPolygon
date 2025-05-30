package ErikRadovan.integrityPolygon.CheckSumLogic;

import java.util.Optional;

public interface ChecksumDatabase {
    Optional<String> getChecksum(String moduleFileName);
}

