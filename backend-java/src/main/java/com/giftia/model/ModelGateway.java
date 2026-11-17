package com.giftia.model;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Model I/O; all workflow and memory policy stays in Java application code. */
public interface ModelGateway {
    String complete(String system, String user, double temperature);
    List<List<Double>> embed(List<String> texts);
    void stream(List<ModelMessage> messages, Consumer<String> onToken);
    Map<String, Object> info();
    Map<String, Object> presets();
    boolean configured();
    boolean healthy();
}
