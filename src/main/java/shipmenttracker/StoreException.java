package shipmenttracker;

/** Thrown when the database cannot be reached or a query fails. */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
