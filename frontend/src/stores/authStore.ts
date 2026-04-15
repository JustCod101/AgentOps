import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface AuthState {
  token: string | null;
  expiresAt: string | null;
  isAuthenticated: boolean;
  login: (token: string, expiresIn: number) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      expiresAt: null,
      isAuthenticated: false,
      login: (token: string, expiresIn: number) => {
        const expiresAt = new Date(Date.now() + expiresIn * 1000).toISOString();
        set({ token, expiresAt, isAuthenticated: true });
      },
      logout: () => {
        set({ token: null, expiresAt: null, isAuthenticated: false });
        localStorage.removeItem('agentops_token');
      },
    }),
    {
      name: 'agentops_auth',
      partialize: (state) => ({
        token: state.token,
        expiresAt: state.expiresAt,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
);
