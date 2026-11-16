package com.habit66.notify;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.config.AppProperties;

/**
 * Web Push 서버 키. 설정에 있으면 그것을 쓰고, 없으면 data/vapid.json 을 읽거나 처음 한 번 만들어 저장한다.
 * 공개키는 비압축 P-256 점 65바이트, 개인키는 32바이트, 둘 다 패딩 없는 base64url 이다.
 */
@Component
public class VapidKeys {

    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);

    private final String publicKey;
    private final String privateKey;

    public VapidKeys(AppProperties props, ObjectMapper mapper) {
        AppProperties.Push push = props.push();
        if (!push.publicKey().isBlank() && !push.privateKey().isBlank()) {
            this.publicKey = push.publicKey().trim();
            this.privateKey = push.privateKey().trim();
            return;
        }
        Path file = props.dataPath().resolve("vapid.json");
        String[] pair;
        if (!Files.exists(file)) {
            pair = generate();
            store(file, pair, mapper);
        } else {
            byte[] raw = readBytes(file);
            if (raw == null) {
                // 파일은 있는데 읽지 못했다(백신·동기화 프로그램의 잠금 등 일시적일 수 있다). 새 키로 덮어쓰면
                // 모든 구독이 무효가 되므로 이번 실행만 푸시를 끈다. 다시 시작하면 다시 읽는다.
                log.error("Web Push is disabled for this run: cannot read {}. Restart the server to retry.", file);
                this.publicKey = null;
                this.privateKey = null;
                return;
            }
            pair = parse(raw, mapper);
            if (pair == null) {
                // 내용이 실제로 깨졌다: 옆으로 옮겨 남기고 새로 만든다(기존 구독은 앱이 열릴 때 다시 구독된다).
                Path broken = file.resolveSibling("vapid.json.broken-" + System.currentTimeMillis());
                try {
                    Files.move(file, broken);
                    log.warn("Moved unusable {} to {}; new VAPID keys will be generated", file, broken.getFileName());
                } catch (IOException e) {
                    log.warn("Cannot move unusable {}: {}", file, e.toString());
                }
                pair = generate();
                store(file, pair, mapper);
            }
        }
        this.publicKey = pair[0];
        this.privateKey = pair[1];
    }

    /** 몇 번 다시 읽어 본다. 끝내 못 읽으면 null. */
    private static byte[] readBytes(Path file) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                log.warn("Cannot read {} (attempt {}): {}", file, attempt, e.toString());
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /** 내용이 올바른 키 쌍이면 [공개키, 개인키], 아니면 null. */
    private static String[] parse(byte[] raw, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(raw);
            String pub = node.path("public_key").asText("");
            String priv = node.path("private_key").asText("");
            if (decodedLength(pub) == 65 && decodedLength(priv) == 32) {
                return new String[] {pub, priv};
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Unusable VAPID key file: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    /** 키를 쓸 수 있는가(파일을 못 읽은 실행에서는 false, 푸시가 꺼진다). */
    public boolean available() {
        return publicKey != null && privateKey != null;
    }

    private static int decodedLength(String value) {
        try {
            return Base64.getUrlDecoder().decode(value).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** 임시 파일에 쓴 뒤 바꿔치기해서, 저장 도중 꺼져도 반쯤 쓰인 파일이 남지 않게 한다. */
    private static void store(Path file, String[] pair, ObjectMapper mapper) {
        Map<String, String> json = new LinkedHashMap<>();
        json.put("public_key", pair[0]);
        json.put("private_key", pair[1]);
        Path temp = file.resolveSibling("vapid.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), json);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Generated VAPID keys at {}", file);
        } catch (IOException | RuntimeException e) {
            // 저장하지 못해도 이번 실행은 메모리의 키로 동작한다(재시작하면 키가 바뀐다).
            log.error("Cannot store VAPID keys at {}: {}", file, e.toString());
        }
    }

    public String publicKey() {
        return publicKey;
    }

    String privateKey() {
        return privateKey;
    }

    /** [공개키, 개인키]. JDK 기본 EC(secp256r1)로 만든다. */
    static String[] generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) pair.getPublic();
            byte[] point = new byte[65];
            point[0] = 0x04;
            System.arraycopy(fixed(pub.getW().getAffineX()), 0, point, 1, 32);
            System.arraycopy(fixed(pub.getW().getAffineY()), 0, point, 33, 32);
            byte[] secret = fixed(((ECPrivateKey) pair.getPrivate()).getS());
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new String[] {encoder.encodeToString(point), encoder.encodeToString(secret)};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate VAPID keys", e);
        }
    }

    /** 부호 바이트를 떼고 앞을 0 으로 채워 32바이트로 맞춘다. */
    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }
}
