package com.pizhai.exception;

/**
 * HTML转PDF功能的自定义异常类
 */
public class HtmlToPdfException extends RuntimeException {

    public HtmlToPdfException(String message) {
        super(message);
    }

    public HtmlToPdfException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class ChromeNotFoundException extends HtmlToPdfException {
        public ChromeNotFoundException(String message) {
            super(message);
        }

        public ChromeNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ConnectionException extends HtmlToPdfException {
        public ConnectionException(String message) {
            super(message);
        }

        public ConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PageNavigationException extends HtmlToPdfException {
        public PageNavigationException(String message) {
            super(message);
        }

        public PageNavigationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PdfGenerationException extends HtmlToPdfException {
        public PdfGenerationException(String message) {
            super(message);
        }

        public PdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
} 