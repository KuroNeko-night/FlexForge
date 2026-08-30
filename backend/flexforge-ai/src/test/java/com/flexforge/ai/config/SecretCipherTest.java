package com.flexforge.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S4 第二通道密文器：往返、随机 IV、篡改/错钥不可解（docs/13 §3.6-5）。 */
class SecretCipherTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef";

    @Test
    void encryptDecryptRoundTrip() {
        SecretCipher cipher = new SecretCipher(SECRET);
        String encoded = cipher.encrypt("sk-demo-key-123456");
        assertThat(encoded).doesNotContain("sk-demo-key-123456");
        assertThat(cipher.decrypt(encoded)).isEqualTo("sk-demo-key-123456");
    }

    @Test
    void randomIvMakesCiphertextsDiffer() {
        SecretCipher cipher = new SecretCipher(SECRET);
        assertThat(cipher.encrypt("same-plaintext")).isNotEqualTo(cipher.encrypt("same-plaintext"));
    }

    @Test
    void tamperedCiphertextFailsClosed() {
        SecretCipher cipher = new SecretCipher(SECRET);
        String encoded = cipher.encrypt("sk-demo-key-123456");
        byte[] raw = java.util.Base64.getDecoder().decode(encoded);
        raw[raw.length - 1] ^= 0x01;
        assertThat(cipher.decrypt(java.util.Base64.getEncoder().encodeToString(raw))).isNull();
    }

    @Test
    void rotatedSecretCannotDecryptOldCiphertext() {
        String encoded = new SecretCipher(SECRET).encrypt("sk-demo-key-123456");
        assertThat(new SecretCipher("rotated-secret-abcdef0123456789").decrypt(encoded)).isNull();
    }

    @Test
    void blankSecretFailsOnlyAtEncryptUsePoint() {
        SecretCipher cipher = new SecretCipher(" ");
        assertThat(cipher.decrypt("anything")).isNull();
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET");
    }
}
