package com.checkout;

import com.checkout.common.CheckoutUtils;
import com.checkout.common.ErrorResponse;
import com.checkout.common.Resource;
import com.checkout.payments.AlternativePaymentSourceResponse;
import com.checkout.payments.CardSource;
import com.checkout.payments.CardSourceResponse;
import com.checkout.payments.ResponseSource;
import com.google.gson.*;
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ApiClientImpl implements ApiClient {
    private static final int UNPROCESSABLE = 422;
    private static final Logger logger = Logger.getLogger("com.checkout.ApiClientImpl");
    private static final Gson DEFAULT_OBJECT_MAPPER = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class, (JsonSerializer<LocalDate>) (LocalDate date, Type typeOfSrc, JsonSerializationContext context) ->
                    new JsonPrimitive(date.format(DateTimeFormatter.ISO_LOCAL_DATE)))
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (Instant date, Type typeOfSrc, JsonSerializationContext context) ->
                    new JsonPrimitive(date.toString()))
            .registerTypeAdapter(LocalDate.class, (JsonDeserializer<LocalDate>) (JsonElement json, Type typeOfSrc, JsonDeserializationContext context) ->
                    LocalDate.parse(json.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (JsonElement json, Type typeOfSrc, JsonDeserializationContext context) ->
                    Instant.parse(json.getAsString()))
            .registerTypeAdapterFactory(RuntimeTypeAdapterFactory.of(ResponseSource.class, "type", true, AlternativePaymentSourceResponse.class)
                    .registerSubtype(CardSourceResponse.class, CardSource.TYPE_NAME))
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private final CheckoutConfiguration configuration;
    private final Gson serializer;

    public ApiClientImpl(CheckoutConfiguration configuration) {
        this(configuration, DEFAULT_OBJECT_MAPPER);
    }

    public ApiClientImpl(CheckoutConfiguration configuration, Gson serializer) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        if (serializer == null) {
            throw new IllegalArgumentException("serializer must not be null");
        }

        this.configuration = configuration;
        this.serializer = serializer;
    }

    @Override
    public <T> CompletableFuture<T> getAsync(String path, ApiCredentials credentials, Class<T> responseType) {
        if (CheckoutUtils.isNullOrEmpty(path)) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }

        return sendRequestAsync("GET", path, credentials, null)
                .thenApply(httpUrlConnection -> deserializeJsonAsync(httpUrlConnection, responseType));
    }

    @Override
    public <T> CompletableFuture<T> postAsync(String path, ApiCredentials credentials, Class<T> responseType, Object request) {
        if (CheckoutUtils.isNullOrEmpty(path)) {
            throw new IllegalArgumentException("path must not be null or blank");
        }
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }

        return sendRequestAsync("POST", path, credentials, request)
                .thenApply(httpUrlConnection -> deserializeJsonAsync(httpUrlConnection, responseType));
    }

    @Override
    public CompletableFuture<? extends Resource> postAsync(String path, ApiCredentials credentials, Map<Integer, Class<? extends Resource>> resultTypeMappings, Object request) {
        if (CheckoutUtils.isNullOrEmpty(path)) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }
        if (resultTypeMappings == null) {
            throw new IllegalArgumentException("resultTypeMappings must not be null");
        }

        return sendRequestAsync("POST", path, credentials, request)
                .thenApply(httpUrlConnection -> {
                    try {
                        Class<? extends Resource> resultType = resultTypeMappings.get(httpUrlConnection.getResponseCode());
                        if (resultType == null) {
                            throw new IllegalStateException("The status code " + httpUrlConnection.getResponseCode() + " is not mapped to a result type");
                        }
                        return deserializeJsonAsync(httpUrlConnection, resultType);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private <T> T deserializeJsonAsync(HttpURLConnection httpUrlConnection, Class<T> resultType) {
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader((
                httpUrlConnection.getErrorStream() == null ? httpUrlConnection.getInputStream() : httpUrlConnection.getErrorStream()
        )))) {
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            String json = stringBuilder.toString();
            logger.info(json);
            return serializer.fromJson(json, resultType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<HttpURLConnection> sendRequestAsync(String httpMethod, String path, ApiCredentials credentials, Object request) {
        if (CheckoutUtils.isNullOrEmpty(path)) {
            throw new IllegalArgumentException("path must not be null or empty");
        }

        try {
            HttpURLConnection httpUrlConnection = (HttpURLConnection) getRequestUrl(path).openConnection();
            httpUrlConnection.setRequestProperty("user-agent", "checkout-sdk-java/" + CheckoutUtils.getVersionFromManifest());
            httpUrlConnection.setRequestProperty("Accept", "application/json");
            httpUrlConnection.setRequestProperty("Content-Type", "application/json");
            httpUrlConnection.setRequestMethod(httpMethod);
            httpUrlConnection.setDoInput(true);
            httpUrlConnection.setDoOutput(true);
            credentials.authorizeAsync(httpUrlConnection);

            if (request != null) {
                String json = serializer.toJson(request);
                logger.info(json);
                try (OutputStream os = httpUrlConnection.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                switch (httpMethod) {
                    case "POST":
                        try (OutputStream os = httpUrlConnection.getOutputStream()) {
                            os.write("".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    case "GET":
                        httpUrlConnection.connect();
                        break;
                }
            }

            logger.info(httpMethod + " " + httpUrlConnection.getURL());
            return CompletableFuture.supplyAsync(() -> {
                try {
                    httpUrlConnection.connect();
                    return httpUrlConnection;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            })
                    .thenApply(this::validateResponseAsync);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpURLConnection validateResponseAsync(HttpURLConnection httpUrlConnection) {
        try {
            if (!CheckoutUtils.isSuccessHttpStatusCode(httpUrlConnection.getResponseCode())) {
                String requestId = httpUrlConnection.getHeaderFields().getOrDefault("Cko-Request-Id", Collections.singletonList("NO_REQUEST_ID_SUPPLIED")).get(0);

                if (httpUrlConnection.getResponseCode() == UNPROCESSABLE) {
                    ErrorResponse error = deserializeJsonAsync(httpUrlConnection, ErrorResponse.class);
                    throw new CheckoutValidationException(error, httpUrlConnection.getResponseCode(), requestId);
                }

                if (httpUrlConnection.getResponseCode() == 404) {
                    throw new CheckoutResourceNotFoundException(requestId);
                }

                throw new CheckoutApiException(httpUrlConnection.getResponseCode(), requestId);
            }
            return httpUrlConnection;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private URL getRequestUrl(String path) {
        URI baseUri = URI.create(configuration.getUri());
        try {
            return baseUri.resolve(path).toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}