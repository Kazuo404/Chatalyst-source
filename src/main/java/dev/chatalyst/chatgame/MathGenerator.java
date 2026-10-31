package dev.chatalyst.chatgame;

import java.util.Random;

/**
 * Preguntas de matematicas inventadas al vuelo: nunca se repiten y no hay que escribirlas.
 *
 * <p>Todas tienen respuesta ENTERA. Una division con decimales en el chat es una discusion
 * sobre cuantas cifras escribir, no una pregunta de matematicas.
 */
public final class MathGenerator {

    /** Que tipo de cuentas sale. */
    public enum Level {
        /** Sumas, restas, multiplicaciones, divisiones exactas, dobles y mitades. */
        SIMPLE,
        /** Operaciones combinadas, potencias, raices, porcentajes, ecuaciones, areas y mas. */
        COMPLEX;

        public static Level parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "math_simple", "matematicas_simples", "simple" -> SIMPLE;
                case "math_complex", "matematicas_complejas", "complex" -> COMPLEX;
                default -> null;
            };
        }
    }

    private MathGenerator() {
    }

    public static ChatGameQuestion generate(String category, Level level, Random random) {
        return level == Level.SIMPLE ? simple(category, random) : complex(category, random);
    }

    private static ChatGameQuestion simple(String category, Random random) {
        int a = 2 + random.nextInt(89);
        int b = 2 + random.nextInt(89);
        return switch (random.nextInt(7)) {
            case 0 -> q(category, "¿Cuánto es " + a + " + " + b + "?", "What is " + a + " + " + b + "?", a + b);
            case 1 -> {
                int hi = Math.max(a, b), lo = Math.min(a, b);
                yield q(category, "¿Cuánto es " + hi + " − " + lo + "?", "What is " + hi + " − " + lo + "?", hi - lo);
            }
            case 2 -> {
                int x = 2 + random.nextInt(11), y = 2 + random.nextInt(11);
                yield q(category, "¿Cuánto es " + x + " × " + y + "?", "What is " + x + " × " + y + "?", x * y);
            }
            case 3 -> {
                int divisor = 2 + random.nextInt(11), result = 2 + random.nextInt(11);
                int n = divisor * result;
                yield q(category, "¿Cuánto es " + n + " ÷ " + divisor + "?", "What is " + n + " ÷ " + divisor + "?", result);
            }
            case 4 -> q(category, "¿Cuál es el doble de " + a + "?", "What is double " + a + "?", a * 2);
            case 5 -> {
                int even = 2 * (5 + random.nextInt(95));
                yield q(category, "¿Cuál es la mitad de " + even + "?", "What is half of " + even + "?", even / 2);
            }
            default -> {
                int c = 2 + random.nextInt(30);
                yield q(category, "¿Cuánto es " + a + " + " + b + " − " + c + "?",
                        "What is " + a + " + " + b + " − " + c + "?", a + b - c);
            }
        };
    }

    private static ChatGameQuestion complex(String category, Random random) {
        return switch (random.nextInt(11)) {
            case 0 -> {
                int a = 3 + random.nextInt(17), b = 3 + random.nextInt(17), c = 5 + random.nextInt(95);
                yield q(category, "¿Cuánto es " + a + " × " + b + " + " + c + "?",
                        "What is " + a + " × " + b + " + " + c + "?", a * b + c, 2);
            }
            case 1 -> {
                int a = 2 + random.nextInt(9), b = 2 + random.nextInt(9), c = 2 + random.nextInt(9);
                yield q(category, "¿Cuánto es (" + a + " + " + b + ") × " + c + "?",
                        "What is (" + a + " + " + b + ") × " + c + "?", (a + b) * c, 2);
            }
            case 2 -> {
                int base = 2 + random.nextInt(14);
                int exp = base <= 5 ? 3 : 2;
                int value = (int) Math.pow(base, exp);
                yield q(category, "¿Cuánto es " + base + "^" + exp + "?", "What is " + base + "^" + exp + "?", value, 2);
            }
            case 3 -> {
                int root = 4 + random.nextInt(22);
                yield q(category, "¿Cuál es la raíz cuadrada de " + (root * root) + "?",
                        "What is the square root of " + (root * root) + "?", root, 2);
            }
            case 4 -> {
                int[] percents = {5, 10, 15, 20, 25, 30, 40, 50, 75};
                int p = percents[random.nextInt(percents.length)];
                int whole = 20 * (1 + random.nextInt(25));
                yield q(category, "¿Cuánto es el " + p + " % de " + whole + "?",
                        "What is " + p + "% of " + whole + "?", p * whole / 100, 2);
            }
            case 5 -> {
                int a = 2 + random.nextInt(8), x = 1 + random.nextInt(15), b = 1 + random.nextInt(30);
                yield q(category, "Resuelve: " + a + "x + " + b + " = " + (a * x + b) + ". ¿Cuánto vale x?",
                        "Solve: " + a + "x + " + b + " = " + (a * x + b) + ". What is x?", x, 3);
            }
            case 6 -> {
                int n = 3 + random.nextInt(5);
                int f = 1;
                for (int i = 2; i <= n; i++) f *= i;
                yield q(category, "¿Cuánto es " + n + "! (factorial)?", "What is " + n + "! (factorial)?", f, 3);
            }
            case 7 -> {
                int w = 3 + random.nextInt(18), h = 3 + random.nextInt(18);
                yield q(category, "¿Cuál es el área de un rectángulo de " + w + " × " + h + "?",
                        "What is the area of a " + w + " × " + h + " rectangle?", w * h, 2);
            }
            case 8 -> {
                int start = 1 + random.nextInt(20), step = 2 + random.nextInt(9);
                String seq = start + ", " + (start + step) + ", " + (start + 2 * step) + ", " + (start + 3 * step);
                yield q(category, "¿Qué número sigue? " + seq + ", ...", "What comes next? " + seq + ", ...",
                        start + 4 * step, 2);
            }
            case 9 -> {
                int a = 2 + random.nextInt(9), b = 2 + random.nextInt(9);
                int lcm = a * b / gcd(a, b);
                yield q(category, "¿Cuál es el mínimo común múltiplo de " + a + " y " + b + "?",
                        "What is the least common multiple of " + a + " and " + b + "?", lcm, 3);
            }
            default -> {
                int a = 10 + random.nextInt(90), b = 10 + random.nextInt(90);
                yield q(category, "¿Cuánto es " + a + " × " + b + "?", "What is " + a + " × " + b + "?", a * b, 3);
            }
        };
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static ChatGameQuestion q(String category, String es, String en, int answer) {
        return q(category, es, en, answer, 1);
    }

    private static ChatGameQuestion q(String category, String es, String en, int answer, int difficulty) {
        return ChatGameQuestion.same(category, es, en, Integer.toString(answer), difficulty);
    }
}
