package org.home.data.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Фейковый Kraken REST: отдаёт заготовленные JSON по пути, ловит тела POST; может имитировать сбой сети. */
class FakeKrakenApi implements KrakenApi {

    final Map<String, String> getResponses = new HashMap<>();
    final Map<String, String> postResponses = new HashMap<>();
    final List<String> posts = new ArrayList<>();
    String lastPostBody;
    RuntimeException failure;                 // если задано — get/post бросают (имитация разрыва)

    @Override public String get(String path, boolean signed) {
        if (failure != null) throw failure;
        return getResponses.getOrDefault(path, "{}");
    }

    @Override public String post(String path, String formBody) {
        if (failure != null) throw failure;
        lastPostBody = formBody;
        posts.add(path + " " + formBody);
        return postResponses.getOrDefault(path,
                "{\"result\":\"success\",\"sendStatus\":{\"order_id\":\"o1\",\"status\":\"placed\",\"orderEvents\":[]}}");
    }
}
