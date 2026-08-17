package app.nimarkogram.messenger.api.dto;

import com.google.gson.annotations.SerializedName;

public final class BadgeDTO {

    @SerializedName("documentId")
    private final long documentId;

    @SerializedName("text")
    private String text;

    public BadgeDTO(long documentId, String text) {
        this.documentId = documentId;
        this.text = text;
    }

    public long getDocumentId() {
        return documentId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public BadgeDTO copy(long documentId, String text) {
        return new BadgeDTO(documentId, text);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BadgeDTO)) return false;
        BadgeDTO b = (BadgeDTO) other;
        if (documentId != b.documentId) return false;
        return text == null ? b.text == null : text.equals(b.text);
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(documentId) * 31;
        return h + (text == null ? 0 : text.hashCode());
    }

    @Override
    public String toString() {
        return "BadgeDTO(documentId=" + documentId + ", text=" + text + ')';
    }
}
