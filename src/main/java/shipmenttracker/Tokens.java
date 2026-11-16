package shipmenttracker;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Issues and checks the signed (HS256) tokens users get from /login. */
public final class Tokens {

    private static final String ISSUER = "shipmenttracker";
    private static final int MIN_SECRET_BYTES = 32;

    /** What kind of organisation a user belongs to. */
    public enum OrgType {
        TENANT,
        CARRIER;

        static OrgType parse(String s) {
            try {
                return s == null ? null : valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Who is calling, taken from a valid token: a user and the tenant or carrier they belong to. */
    public record Principal(long userId, OrgType orgType, long orgId, String username) {

        public boolean isCarrier() {
            return orgType == OrgType.CARRIER;
        }
    }

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final Duration ttl;
    private final Clock clock;

    public Tokens(String secret, Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    Tokens(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("JWT secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        // The verifier pins HS256, so tokens with "alg": "none" or another algorithm are rejected.
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).acceptLeeway(5).build();
        this.ttl = ttl;
        this.clock = clock;
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(Principal p) {
        Instant now = clock.instant();
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(Long.toString(p.userId()))
                .withClaim("orgType", p.orgType().name())
                .withClaim("orgId", p.orgId())
                .withClaim("username", p.username())
                .withIssuedAt(now)
                .withExpiresAt(now.plus(ttl))
                .sign(algorithm);
    }

    public Optional<Principal> verify(String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);
            OrgType orgType = OrgType.parse(jwt.getClaim("orgType").asString());
            Long orgId = jwt.getClaim("orgId").asLong();
            String username = jwt.getClaim("username").asString();
            // Tokens from before carriers existed have no orgType, so they are refused.
            if (jwt.getSubject() == null || orgType == null || orgId == null || username == null) {
                return Optional.empty();
            }
            return Optional.of(new Principal(Long.parseLong(jwt.getSubject()), orgType, orgId, username));
        } catch (JWTVerificationException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
