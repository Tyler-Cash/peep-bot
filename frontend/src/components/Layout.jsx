import React, {useState, useEffect, useRef} from 'react';
import {Outlet, useLocation, useNavigate} from "react-router-dom";

export default function Layout() {
    const location = useLocation();
    const navigate = useNavigate();
    const [toast, setToast] = useState(null);
    const [exiting, setExiting] = useState(false);
    const timerRef = useRef(null);

    useEffect(() => {
        if (location.state?.toast) {
            const message = location.state.toast;
            navigate(location.pathname, {replace: true, state: {}});

            clearTimeout(timerRef.current);
            setExiting(false);
            setToast(message);

            timerRef.current = setTimeout(() => {
                setExiting(true);
                setTimeout(() => {
                    setToast(null);
                    setExiting(false);
                }, 200);
            }, 3000);
        }
    }, [location.state?.toast]);

    return (
        <div className="app-layout">
            {toast && (
                <div className="toast-container">
                    <div className={`toast-message ${exiting ? 'toast-exit' : ''}`}>
                        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="#4ade80"
                             viewBox="0 0 16 16">
                            <path
                                d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0m-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05z"/>
                        </svg>
                        {toast}
                    </div>
                </div>
            )}
            <main className="page-enter" key={location.pathname}>
                <Outlet/>
            </main>
            <footer className="app-footer">
                <span className="app-footer-text">Peep Bot</span>
            </footer>
        </div>
    );
}
