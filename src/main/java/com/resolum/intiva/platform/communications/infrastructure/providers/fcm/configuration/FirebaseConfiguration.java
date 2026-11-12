package com.resolum.intiva.platform.communications.infrastructure.providers.fcm.configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

@Configuration
public class FirebaseConfiguration {

    @Value("${firebase.project.id:}")
    private String projectId;

    @Value("${firebase.credentials.path:}")
    private String credentialsPath;

    @Value("${firebase.credentials.base64:}")
    private String credentialsBase64;

    @Value("${integrations.fcm.enabled:false}")
    private boolean enabled;

    @Bean
    FirebaseSettings firebaseMessagingSettings() {
        return new FirebaseSettings(projectId, credentialsPath, enabled);
    }

    @Bean
    @ConditionalOnProperty(name = "integrations.fcm.enabled", havingValue = "true")
    FirebaseApp firebaseApp(FirebaseSettings settings) throws IOException {

        if (FirebaseApp.getApps().isEmpty()) {
            try (InputStream serviceAccount = resolveCredentials()) {
                var options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .setProjectId(settings.projectId())
                        .build();

                return FirebaseApp.initializeApp(options);
            }
        }
        return FirebaseApp.getInstance();
    }

    /**
     * Resuelve las credenciales desde Base64 (Azure) o desde archivo (local).
     * Prioridad: Base64 > archivo físico.
     */
    private InputStream resolveCredentials() throws IOException {
        if (credentialsBase64 != null && !credentialsBase64.isBlank()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(credentialsBase64);
                return new ByteArrayInputStream(decoded);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("firebase.credentials.base64 is not valid Base64.", e);
            }
        }
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            validateCredentialsPath(credentialsPath);
            return new FileInputStream(credentialsPath);
        }

        throw new IllegalStateException(
                "Firebase credentials not configured. " +
                        "Set firebase.credentials.base64 (Azure) or firebase.credentials.path (local)."
        );
    }

    private void validateCredentialsPath(String credentialsPath) {
        if (credentialsPath.contains("*") || credentialsPath.contains("?")) {
            throw new IllegalStateException(
                    "firebase.credentials.path must point to one concrete JSON file. Wildcards are not supported: " + credentialsPath
            );
        }

        var path = Path.of(credentialsPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "firebase.credentials.path must point to an existing JSON file. Current value: " + credentialsPath
            );
        }
    }
}