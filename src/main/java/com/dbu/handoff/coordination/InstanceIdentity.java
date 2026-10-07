package com.dbu.handoff.coordination;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who this process is.
 *
 * <p>Socket ownership is recorded against an instance, so every instance needs
 * a stable identity for its lifetime. In ECS this is the task ID; locally it is
 * a random value generated at startup.
 *
 * <p>The identity must not survive a restart: a restarted process has lost its
 * sockets, and reusing the old identity would let it appear to still own them.
 */
@Component
public class InstanceIdentity {

    private final String id;

    public InstanceIdentity() {
        String fromEnv = System.getenv("ECS_TASK_ID");
        this.id = (fromEnv != null && !fromEnv.isBlank())
                ? fromEnv
                : "local-" + UUID.randomUUID().toString().substring(0, 8);
    }

    InstanceIdentity(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return id;
    }
}
