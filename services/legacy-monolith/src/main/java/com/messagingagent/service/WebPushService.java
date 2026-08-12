package com.messagingagent.service;

import com.messagingagent.model.PushSubscription;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Security;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import java.util.Base64;
import java.util.List;

@Service
@Slf4j
public class WebPushService {

    @Value("${vapid.public.key:}")
    private String publicKey;
    
    @Value("${vapid.private.key:}")
    private String privateKey;
    
    @Value("${vapid.subject:mailto:admin@localhost}")
    private String subject;

    private PushService pushService;

    @PostConstruct
    public void init() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            if (publicKey == null || publicKey.trim().isEmpty()) {
                log.warn("VAPID keys not configured, generating ephemeral keys for this session.");
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("ECDSA", BouncyCastleProvider.PROVIDER_NAME);
                keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = keyPairGenerator.generateKeyPair();
                
                pushService = new PushService();
                pushService.setPublicKey((ECPublicKey) pair.getPublic());
                pushService.setPrivateKey((ECPrivateKey) pair.getPrivate());
                pushService.setSubject(subject);
                
                byte[] pubKey = nl.martijndwars.webpush.Utils.encode((ECPublicKey) pair.getPublic());
                this.publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pubKey);
                log.info("Ephemeral VAPID Public Key: {}", this.publicKey);
            } else {
                pushService = new PushService();
                pushService.setPublicKey(publicKey);
                pushService.setPrivateKey(privateKey);
                pushService.setSubject(subject);
            }
        } catch (Exception e) {
            log.error("Failed to initialize WebPushService", e);
        }
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void sendNotifications(List<PushSubscription> subscriptions, String title, String body, String url) {
        String payload = String.format("{\"title\":\"%s\", \"body\":\"%s\", \"url\":\"%s\"}", title, body, url == null ? "/" : url);
        
        for (PushSubscription sub : subscriptions) {
            try {
                Notification notification = new Notification(
                    sub.getEndpoint(),
                    sub.getP256dh(),
                    sub.getAuth(),
                    payload.getBytes()
                );
                pushService.send(notification);
                log.info("Sent web push to {}", sub.getEndpoint());
            } catch (Exception e) {
                log.error("Failed to send push notification to {}", sub.getEndpoint(), e);
            }
        }
    }
}
