package com.exteragram.messenger.ai;

import com.exteragram.messenger.ai.data.Role;

import java.util.ArrayList;
import java.util.List;

public class AiController {

    private static final AiController INSTANCE = new AiController();

    private final app.nimarkogram.messenger.ai.AiController impl =
            app.nimarkogram.messenger.ai.AiController.getInstance();

    public AiController() {
    }

    public static AiController getInstance() {
        return INSTANCE;
    }

    public static boolean canUseAI() {
        return app.nimarkogram.messenger.ai.AiController.canUseAI();
    }

    public List<Role> getRoles() {
        return wrap(impl.getRoles());
    }

    public boolean addRole(Role role) {
        return impl.addRole(role);
    }

    public boolean removeRole(Role role) {
        return impl.removeRole(role);
    }

    public boolean updateRole(Role oldRole, Role newRole) {
        return impl.updateRole(oldRole, newRole);
    }

    public boolean isCustomRole(Role role) {
        return impl.isCustomRole(role);
    }

    public List<Role> getSuggestedRoles() {
        return wrap(impl.getSuggestedRoles());
    }

    public boolean isSuggestedRole(Role role) {
        return impl.isSuggestedRole(role);
    }

    public Role getSelectedRole() {
        return asExtera(impl.getSelectedRole());
    }

    public void loadRoles() {
        impl.loadRoles();
    }

    public void saveRoles() {
        impl.saveRoles();
    }

    public static boolean canSendImage(String path) {
        return app.nimarkogram.messenger.ai.AiController.canSendImage(path);
    }

    private static List<Role> wrap(List<app.nimarkogram.messenger.ai.data.Role> in) {
        ArrayList<Role> out = new ArrayList<>();
        if (in != null) {
            for (app.nimarkogram.messenger.ai.data.Role r : in) {
                Role e = asExtera(r);
                if (e != null) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private static Role asExtera(app.nimarkogram.messenger.ai.data.Role r) {
        if (r == null) {
            return null;
        }
        if (r instanceof Role) {
            return (Role) r;
        }
        Role e = new Role(r.getName(), r.getPrompt());
        e.setSuggestion(r.isSuggestion());
        return e;
    }
}
