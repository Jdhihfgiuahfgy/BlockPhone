package dev.blockphone.common.apps;

/**
 * Итог действия приложения.
 *
 * @param toast    сообщение игроку ("" — без сообщения); строка с «@» в начале — ключ перевода телефона
 * @param error    сообщение — ошибка (красное), страница не меняется
 * @param navigate куда перейти: "" — остаться, "refresh" — перерисовать текущую, "back", "close" или id страницы
 */
public record AppResult(String toast, boolean error, String navigate) {
    public static AppResult ok() {
        return new AppResult("", false, "");
    }

    public static AppResult ok(String toast) {
        return new AppResult(toast == null ? "" : toast, false, "");
    }

    public static AppResult error(String message) {
        return new AppResult(message == null ? "" : message, true, "");
    }

    public static AppResult open(String page) {
        return new AppResult("", false, page);
    }

    public AppResult then(String page) {
        return new AppResult(toast, error, page == null ? "" : page);
    }

    public AppResult withToast(String text) {
        return new AppResult(text == null ? "" : text, error, navigate);
    }
}
