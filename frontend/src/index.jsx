import { initTelemetry } from './telemetry';
initTelemetry();

import React from 'react';
import 'bootstrap/dist/css/bootstrap.css';
import './index.css';
import reportWebVitals from './reportWebVitals';
import * as ReactDOM from 'react-dom/client';
import { createBrowserRouter, createRoutesFromElements, Route, RouterProvider } from 'react-router-dom';
import Login from './components/Login';
import Protected from './components/Protected';
import { CookiesProvider } from 'react-cookie';
import configureAppStore from './store';
import { Provider } from 'react-redux';
import LoginSuccess from './components/LoginSuccess';
import { setupListeners } from '@reduxjs/toolkit/query';
import CreateEvent from './components/CreateEvent';
import ListEvents from './components/ListEvents';
import EditEvent from './components/EditEvent';
import Layout from './components/Layout';
import ErrorBoundary from './components/ErrorBoundary';

const router = createBrowserRouter(
    createRoutesFromElements(
        <Route>
            <Route path="/login/success" element={<LoginSuccess />} />
            <Route path="/login" element={<Login />} />
            <Route
                element={
                    <ErrorBoundary>
                        <Layout />
                    </ErrorBoundary>
                }
            >
                <Route
                    path="/event/create"
                    element={
                        <Protected>
                            <CreateEvent />
                        </Protected>
                    }
                />
                <Route
                    path="/event/:id"
                    element={
                        <Protected>
                            <EditEvent />
                        </Protected>
                    }
                />
                <Route
                    path="/event/list"
                    element={
                        <Protected>
                            <ListEvents />
                        </Protected>
                    }
                />
                <Route
                    path="/"
                    element={
                        <Protected>
                            <ListEvents />
                        </Protected>
                    }
                />
            </Route>
        </Route>,
    ),
);

const store = configureAppStore();
setupListeners(store.dispatch);

ReactDOM.createRoot(document.getElementById('root')).render(
    <React.StrictMode>
        <Provider store={store}>
            <CookiesProvider defaultSetOptions={{ path: '/' }}>
                <RouterProvider router={router} />
            </CookiesProvider>
        </Provider>
    </React.StrictMode>,
);

reportWebVitals((metric) => {
    if (import.meta.env.DEV) {
        console.log('[Web Vital]', metric.name, metric.value);
    }
});
