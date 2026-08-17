package app.nimarkogram.messenger.ai.data;

import java.io.Serializable;
import java.util.Objects;

public class Role implements Comparable<Role>, Serializable {

    public static final Role USER = new Role("user", "");
    public static final Role ASSISTANT = new Role("assistant", "");
    public static final Role SYSTEM = new Role("system", "");

    private boolean isSuggestion;
    private String name;
    private String prompt;

    public Role(String name, String prompt) {
        this.name = name;
        this.prompt = prompt;
    }

    public String getName() {
        return name;
    }

    public String getPrompt() {
        return prompt;
    }

    public boolean isSuggestion() {
        return isSuggestion;
    }

    public Role setSuggestion(boolean suggestion) {
        this.isSuggestion = suggestion;
        return this;
    }

    public boolean isSelected() {
        return false;
    }

    @Override
    public int compareTo(Role role) {
        if (role == null || role.getName() == null) {
            return name == null ? 0 : 1;
        }
        if (name == null) {
            return -1;
        }
        return name.compareTo(role.getName());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        return Objects.equals(name, ((Role) obj).name);
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }
}
