package com.internalops.dashboard;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DashboardEventService {
    private final DashboardQueryService queries;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    public DashboardEventService(DashboardQueryService queries) { this.queries = queries; }

    public SseEmitter subscribe(int requestedDays) {
        int days = normalizeDays(requestedDays);
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(emitter, days);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        emitter.onError(error -> clients.remove(client));
        send(client, "snapshot", queries.snapshot(days));
        return emitter;
    }

    @Scheduled(fixedDelay = 3000)
    public void broadcastSnapshots() {
        clients.stream().map(Client::days).distinct().forEach(days -> {
            DashboardSnapshot snapshot = queries.snapshot(days);
            clients.stream().filter(client -> client.days() == days).forEach(client -> send(client, "snapshot", snapshot));
        });
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() { clients.forEach(client -> send(client, "heartbeat", "ok")); }

    private int normalizeDays(int days) { return days == 7 || days == 90 ? days : 30; }
    private void send(Client client, String name, Object data) {
        try { client.emitter().send(SseEmitter.event().name(name).data(data)); }
        catch (IOException | IllegalStateException error) { clients.remove(client); client.emitter().complete(); }
    }
    private record Client(SseEmitter emitter, int days) {}
}
