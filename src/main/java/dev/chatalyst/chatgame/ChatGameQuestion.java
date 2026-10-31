package dev.chatalyst.chatgame;

import java.util.ArrayList;
import java.util.List;

/**
 * Una pregunta lista para lanzar al chat, en espanol y en ingles.
 *
 * <p>Cada jugador la lee en el idioma de su cliente, y vale responder en cualquiera de los dos:
 * en un servidor mezclado, quien juega en ingles no tiene por que saber que "London" se escribe
 * "Londres".
 *
 * @param category  id de la categoria
 * @param promptEs  la pregunta en espanol
 * @param promptEn  la pregunta en ingles (si falta, se usa la espanola)
 * @param answersEs respuestas que valen en espanol; la primera es la que se muestra
 * @param answersEn respuestas que valen en ingles; la primera se muestra a quien juega en ingles
 */
public record ChatGameQuestion(String category, String promptEs, String promptEn,
                               List<String> answersEs, List<String> answersEn, int difficulty) {

    public ChatGameQuestion {
        answersEs = List.copyOf(answersEs);
        answersEn = List.copyOf(answersEn);
        if (answersEs.isEmpty() && answersEn.isEmpty()) {
            throw new IllegalArgumentException("una pregunta necesita al menos una respuesta");
        }
        if (promptEn == null || promptEn.isBlank()) promptEn = promptEs;
        if (promptEs == null || promptEs.isBlank()) promptEs = promptEn;
        difficulty = Math.max(0, Math.min(3, difficulty));
    }

    /** Sin dificultad propia (0): usa la de su categoria. */
    public ChatGameQuestion(String category, String promptEs, String promptEn,
                            List<String> answersEs, List<String> answersEn) {
        this(category, promptEs, promptEn, answersEs, answersEn, 0);
    }

    /** Pregunta con la misma respuesta en los dos idiomas (numeros, simbolos, formulas). */
    public static ChatGameQuestion same(String category, String es, String en, String answer, int difficulty) {
        return new ChatGameQuestion(category, es, en, List.of(answer), List.of(), difficulty);
    }

    public String prompt(boolean spanish) {
        return spanish ? promptEs : promptEn;
    }

    /** Todo lo que vale como respuesta, en cualquier idioma. */
    public List<String> accepted() {
        List<String> all = new ArrayList<>(answersEs);
        all.addAll(answersEn);
        return all;
    }

    /** Solo las respuestas del idioma de quien contesta (para preguntas de traduccion). */
    public List<String> acceptedFor(boolean spanish) {
        List<String> own = spanish ? answersEs : answersEn;
        return own.isEmpty() ? accepted() : own;
    }

    /** La respuesta que se ensena cuando se acaba el tiempo, en el idioma de quien la lee. */
    public String shownAnswer(boolean spanish) {
        List<String> first = spanish ? answersEs : answersEn;
        List<String> other = spanish ? answersEn : answersEs;
        return !first.isEmpty() ? first.getFirst() : other.getFirst();
    }
}
