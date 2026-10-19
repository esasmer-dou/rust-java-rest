package com.reactor.rust.maven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifyContractMojoTest {
    @Test void rejectsRemovedAndChangedContractsButAllowsNewTypes() {
        var before = Map.of("Customer", "RECORD|id:long", "Status", "ENUM|ACTIVE");
        assertTrue(VerifyContractMojo.incompatible(before, before).isEmpty());
        assertEquals(List.of("Customer", "Status"), VerifyContractMojo.incompatible(
                new TreeMap<>(before), Map.of("Customer", "RECORD|id:java.lang.String")));
        var after = new HashMap<>(before); after.put("NewType", "RECORD|id:long");
        assertTrue(VerifyContractMojo.incompatible(before, after).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> VerifyContractMojo.read(List.of("x\ta", "x\tb")));
    }
}
