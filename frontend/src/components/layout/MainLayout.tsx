import { Outlet, Link, useLocation } from 'react-router-dom';
import { LayoutDashboard, Activity, History, BookOpen, Settings, LogOut } from 'lucide-react';
import { useAuthStore } from '../../stores/authStore';

const navItems = [
  { path: '/', icon: LayoutDashboard, label: 'Dashboard' },
  { path: '/diagnosis', icon: Activity, label: 'Diagnosis' },
  { path: '/history', icon: History, label: 'History' },
  { path: '/knowledge', icon: BookOpen, label: 'Knowledge' },
  { path: '/settings', icon: Settings, label: 'Settings' },
];

export default function MainLayout() {
  const location = useLocation();
  const logout = useAuthStore((state) => state.logout);

  return (
    <div className="flex h-screen bg-gray-100">
      <aside className="w-64 bg-white shadow-lg">
        <div className="p-6">
          <h1 className="text-2xl font-bold text-blue-600">AgentOps</h1>
          <p className="text-sm text-gray-500 mt-1">AIOps Dashboard</p>
        </div>
        <nav className="mt-6">
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = location.pathname === item.path;
            return (
              <Link
                key={item.path}
                to={item.path}
                className={`flex items-center gap-3 px-6 py-3 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-blue-50 text-blue-600 border-r-4 border-blue-600'
                    : 'text-gray-700 hover:bg-gray-50'
                }`}
              >
                <Icon className="w-5 h-5" />
                {item.label}
              </Link>
            );
          })}
        </nav>
        <div className="absolute bottom-0 w-64 p-4 border-t">
          <button
            onClick={logout}
            className="flex items-center gap-3 px-4 py-2 text-sm font-medium text-red-600 hover:bg-red-50 rounded-lg w-full"
          >
            <LogOut className="w-5 h-5" />
            Logout
          </button>
        </div>
      </aside>
      <main className="flex-1 overflow-auto p-8">
        <Outlet />
      </main>
    </div>
  );
}
