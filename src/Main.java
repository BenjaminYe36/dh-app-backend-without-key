import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.HashMap;

import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.sun.net.httpserver.*;

import java.io.*;

public class Main {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
    private static HashMap<String, Boolean> recordMap = new HashMap<>();
    private static JSONParser jsonParser = new JSONParser();

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws IOException {
        initMap();
        JSONObject success = new JSONObject(), fail = new JSONObject();
        success.put("success", true);
        fail.put("success", false);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 100);

        server.createContext("/", (HttpExchange t) -> {
            String html = Files.readString(Paths.get("index.html"));
            send(t, "text/html; charset=utf-8", html);
        });

        server.createContext("/clear", (HttpExchange t) -> {
            initMap();
            send(t, "application/json", success.toJSONString());
        });

        server.createContext("/getResult", (HttpExchange t) -> {
            JSONObject result = new JSONObject();
            for (String key : recordMap.keySet()) {
                result.put(key, recordMap.get(key).toString());
            }
            send(t, "application/json", result.toJSONString());
        });

        server.createContext("/analyze", (HttpExchange t) -> {
            if (!t.getRequestURI().getPath().contains("=")) {
                send(t, "application/json", fail.toJSONString());
            }
            String url = t.getRequestURI().getPath().split("=", 2)[1];
            System.out.println(url);

            boolean isFood = false;

            String json2 = new StringBuilder()
                    .append("{")
                    .append("\"Url\":\"" + url + "\",")
                    .append("}").toString();

            HttpRequest request2 = HttpRequest.newBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(json2))
                    .uri(URI.create("https://eastus2.api.cognitive.microsoft.com/vision/v3.2/analyze?visualFeatures=Tags&language=en&model-version=latest"))
                    .setHeader("Ocp-Apim-Subscription-Key", "")
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response2 = null;
            try {
                response2 = httpClient.send(request2, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            String res2 = response2.body();
//        String res2 = "{\"tags\":[{\"name\":\"fruit\",\"confidence\":0.9983921051025391},{\"name\":\"berry\",\"confidence\":0.994539201259613},{\"name\":\"blueberry\",\"confidence\":0.925489604473114},{\"name\":\"food\",\"confidence\":0.9109729528427124},{\"name\":\"superfood\",\"confidence\":0.9081420302391052},{\"name\":\"huckleberry\",\"confidence\":0.9059849977493286},{\"name\":\"seedless fruit\",\"confidence\":0.9052137136459351},{\"name\":\"bilberry\",\"confidence\":0.9044003486633301},{\"name\":\"natural foods\",\"confidence\":0.8648809194564819},{\"name\":\"blue\",\"confidence\":0.6267929077148438}],\"requestId\":\"d3fc98cc-f218-44b0-aed4-a0e00cfab61a\",\"metadata\":{\"height\":628,\"width\":1200,\"format\":\"Png\"},\"modelVersion\":\"2021-05-01\"}";
            System.out.println(res2);
            JSONArray tags = null;
            try {
                tags = ((JSONArray) ((JSONObject) jsonParser.parse(res2)).get("tags"));
            } catch (ParseException e) {
                e.printStackTrace();
            }
            for (Object obj : tags) {
                JSONObject jObj = (JSONObject) obj;
                if ((((String) jObj.get("name")).equals("fruit") || ((String) jObj.get("name")).equals("food"))
                        && ((Double) jObj.get("confidence")) >= 0.5) {
                    recordMap.put("food fruit", true);
                    isFood = true;
                    break;
                }
            }
            for (String key : recordMap.keySet()) {
                System.out.printf("%s : %b\n", key, recordMap.get(key));
            }

            if (!isFood) {
                String json = new StringBuilder()
                        .append("{")
                        .append("\"Url\":\"" + url + "\",")
                        .append("}").toString();

                HttpRequest request = HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .uri(URI.create("https://distinguishtrash-prediction.cognitiveservices.azure.com/customvision/v3.0/Prediction/5ce516fc-1381-41d8-af6a-fd1b2f10db9a/classify/iterations/Iteration3/url"))
                        .setHeader("Prediction-Key", "")
                        .header("Content-Type", "application/json")
                        .build();

                HttpResponse<String> response = null;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                String res = response.body();
                System.out.println(res);

                JSONObject customResult = null;
                try {
                    customResult = (JSONObject) jsonParser.parse(res);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
                JSONArray predictions = (JSONArray) customResult.get("predictions");
                System.out.println(predictions.toString());
                JSONObject mostLikelyPrediction = ((JSONObject) predictions.get(0));
                if ((Double) mostLikelyPrediction.get("probability") >= 0.5) {
                    System.out.println("got tag");
                    String tagName = (String) mostLikelyPrediction.get("tagName");
                    if (tagName.equals("paper bag") || tagName.equals("plastic bag")) {
                        tagName = "plastic paper bag";
                    }
                    recordMap.put(tagName, true);
                }
                for (String key : recordMap.keySet()) {
                    System.out.printf("%s : %b\n", key, recordMap.get(key));
                }
            }

            send(t, "application/json", success.toJSONString());
        });

        server.setExecutor(null);
        server.start();


    }

    private static void initMap() {
        recordMap.put("can", false);
        recordMap.put("other bottle", false);
        recordMap.put("paper box", false);
        recordMap.put("plastic paper bag", false);
        recordMap.put("plastic bottle", false);
        recordMap.put("wrapper", false);
        recordMap.put("food fruit", false);
    }

    private static void send(HttpExchange t, String contentType, String data)
            throws IOException, UnsupportedEncodingException {
        t.getResponseHeaders().set("Content-Type", contentType);
        byte[] response = data.getBytes("UTF-8");
        t.getResponseHeaders().add("Access-Control-Allow-Origin", "https://dh-waste-advisor.herokuapp.com/");
        t.sendResponseHeaders(200, response.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(response);
        }
    }
}