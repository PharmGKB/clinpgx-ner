package org.clinpgx;

public class DocumentEntity {
    private final String text;
    private final int begin;
    private final int end;
    private final String accessionId;
    private final String type;

    public DocumentEntity(String text, int begin, int end, String accessionId, String type) {
        this.text = text;
        this.begin = begin;
        this.end = end;
        this.accessionId = accessionId;
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public int getBegin() {
        return begin;
    }

    public int getEnd() {
        return end;
    }

    public String getAccessionId() {
        return accessionId;
    }

    public String getType() {
        return type;
    }

    public String toString() {
        return String.format("%d-%d=%s [%s] [%s]", begin, end, text, type, accessionId);
    }
}
