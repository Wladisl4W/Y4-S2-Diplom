package ru.diplom.intonation.audio;

public record Note(int midi, String solfege, String letter, int octave, double cents) {
    private static final String[] SOLFEGE = {"До", "До♯", "Ре", "Ре♯", "Ми", "Фа", "Фа♯", "Соль", "Соль♯", "Ля", "Ля♯", "Си"};
    private static final String[] LETTER = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};

    public static Note fromFrequency(double hz) {
        if (!Double.isFinite(hz) || hz <= 0) throw new IllegalArgumentException("Invalid frequency");
        double exactMidi = 69 + 12 * Math.log(hz / 440.0) / Math.log(2);
        int midi = (int) Math.round(exactMidi);
        int pitchClass = Math.floorMod(midi, 12);
        return new Note(midi, SOLFEGE[pitchClass], LETTER[pitchClass], Math.floorDiv(midi, 12) - 1,
                (exactMidi - midi) * 100);
    }

    public String display() { return solfege + " / " + letter + octave; }
}
