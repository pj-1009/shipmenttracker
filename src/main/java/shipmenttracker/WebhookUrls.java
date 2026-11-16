package shipmenttracker;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Checks webhook URLs before they're saved and again before each send.
 *
 * <p>The app calls these URLs from inside the cluster, so without checks a user
 * could point one at internal services (the database, the Kubernetes API, a
 * cloud metadata endpoint). Unless private addresses are allowed (for local
 * development and tests), only https URLs whose host resolves solely to public
 * addresses are accepted.
 */
final class WebhookUrls {

    static final int MAX_LENGTH = 2000;

    /** The URL can't be used; the message says why. */
    static final class InvalidUrl extends Exception {
        InvalidUrl(String message) {
            super(message);
        }
    }

    private WebhookUrls() {}

    static URI check(String url, boolean allowPrivate) throws InvalidUrl {
        if (url == null || url.isBlank() || url.length() > MAX_LENGTH) {
            throw new InvalidUrl("url required (at most " + MAX_LENGTH + " characters)");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new InvalidUrl("url is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowPrivate && scheme.equals("http"))) {
            throw new InvalidUrl(allowPrivate ? "url must be http or https" : "url must be https");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new InvalidUrl("url must have a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidUrl("url must not contain a username or password");
        }
        if (uri.getRawFragment() != null) {
            throw new InvalidUrl("url must not contain a #fragment");
        }
        if (!allowPrivate) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(uri.getHost());
            } catch (UnknownHostException e) {
                throw new InvalidUrl("url host does not resolve");
            }
            for (InetAddress a : addresses) {
                if (!isPublic(a)) {
                    throw new InvalidUrl("url must point to a public address");
                }
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) {
                return false; // 0.0.0.0/8 "this network"
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false; // 100.64.0.0/10 carrier-grade NAT (also used by some clusters)
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false; // 198.18.0.0/15 benchmarking
            }
            if (first >= 240) {
                return false; // reserved and broadcast
            }
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) {
                return false; // fc00::/7 unique local
            }
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) {
                v4Mapped &= b[i] == 0;
            }
            if (v4Mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }
}
