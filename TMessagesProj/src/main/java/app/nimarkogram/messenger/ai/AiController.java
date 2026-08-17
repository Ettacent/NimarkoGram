package app.nimarkogram.messenger.ai;

import app.nimarkogram.messenger.ai.data.Role;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiController {

    private static class SingletonHolder {
        private static final AiController INSTANCE = new AiController();
    }

    private final List<Role> roles = new ArrayList<>();

    public AiController() {
    }

    public static AiController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public static boolean canUseAI() {
        return false;
    }

    public synchronized List<Role> getRoles() {
        return Collections.unmodifiableList(new ArrayList<>(roles));
    }

    public synchronized boolean addRole(Role role) {
        if (role == null || role.getName() == null || role.getPrompt() == null) {
            return false;
        }
        if (roles.contains(role)) {
            return false;
        }
        return roles.add(role);
    }

    public synchronized boolean removeRole(Role role) {
        if (role == null) {
            return false;
        }
        return roles.remove(role);
    }

    public synchronized boolean updateRole(Role oldRole, Role newRole) {
        int index = roles.indexOf(oldRole);
        if (index == -1 || newRole == null) {
            return false;
        }
        roles.set(index, newRole);
        return true;
    }

    public synchronized boolean isCustomRole(Role role) {
        if (role == null) {
            return false;
        }
        return roles.contains(role);
    }

    public List<Role> getSuggestedRoles() {
        return new ArrayList<>();
    }

    public boolean isSuggestedRole(Role role) {
        return false;
    }

    public Role getSelectedRole() {
        return null;
    }

    public void loadRoles() {
        
    }

    public void saveRoles() {
        
    }

    public static boolean canSendImage(String path) {
        if (path == null) {
            return false;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif");
    }
}
