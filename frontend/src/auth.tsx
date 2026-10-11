import { createContext, useCallback, useContext, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { authApi, getToken, setToken } from "./api";
import type { UserProfile } from "./api";

interface AuthState {
  user: UserProfile | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (body: {
    email: string;
    password: string;
    legal_name: string;
    ubo_tax_id: string;
    country: string;
  }) => Promise<void>;
  logout: () => void;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(Boolean(getToken()));

  const refresh = useCallback(async () => {
    const res = await authApi.me();
    setUser(res.data);
  }, []);

  useEffect(() => {
    if (!getToken()) return;
    refresh()
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, [refresh]);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await authApi.login(email, password);
      setToken(res.data.access_token);
      await refresh();
    },
    [refresh],
  );

  const register = useCallback(
    async (body: Parameters<typeof authApi.register>[0]) => {
      const res = await authApi.register(body);
      setToken(res.data.access_token);
      setUser(res.data.user);
    },
    [],
  );

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, register, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth outside AuthProvider");
  return ctx;
}
