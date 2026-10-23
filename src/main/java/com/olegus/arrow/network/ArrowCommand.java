package com.olegus.arrow.network;

/** Команды, которые клиент отправляет по нажатию клавиш. */
public enum ArrowCommand {
    TOGGLE_MODE("key.olegus_arrow.toggle_mode"),
    DESIGNATE("key.olegus_arrow.designate"),
    WHISTLE("key.olegus_arrow.whistle");

    private final String keyTranslation;

    ArrowCommand(String keyTranslation) {
        this.keyTranslation = keyTranslation;
    }

    /** Ключ привязки клавиши — для подсказок вида «нажмите [R]». */
    public String keyTranslation() {
        return this.keyTranslation;
    }

    public static ArrowCommand byIndex(int index) {
        ArrowCommand[] commands = values();
        return index >= 0 && index < commands.length ? commands[index] : TOGGLE_MODE;
    }
}
