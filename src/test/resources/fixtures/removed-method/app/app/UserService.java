package app;

import lib.Cache;

public class UserService {
    private final Cache cache = new Cache();

    public Object find(String id) {
        Object hit = cache.getIfPresent(id);
        return hit != null ? hit : cache.get(id);
    }
}
