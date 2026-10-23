package connore;

/** Assignment 16: an application-specific checked exception. */
public class AppException extends Exception {
    public final int status;
    public final String code;

    public AppException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public static void require(boolean condition, int status, String code) throws AppException {
        if (!condition) {
            throw new AppException(status, code);
        }
    }
}
