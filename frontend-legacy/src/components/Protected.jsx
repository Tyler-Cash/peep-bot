import React from 'react';
import { Navigate } from 'react-router-dom';
import { useDispatch, useSelector } from 'react-redux';
import { useIsLoggedInQuery } from '../api/eventBotApi';
import { loginSuccess, logout } from '../reducers/authReducer';
import logger from '../utils/logger';

const Protected = ({ children }) => {
    const isAuthenticated = useSelector((state) => state.auth.isAuthenticated);
    const dispatch = useDispatch();
    const { data, error, isSuccess, isLoading } = useIsLoggedInQuery();
    if (isSuccess) {
        dispatch(loginSuccess(data));
    } else if (error) {
        logger.warn('Auth check failed', error);
        dispatch(logout());
    }
    if (isLoading) return null;
    return isAuthenticated ? children : <Navigate to="/login" />;
};

export default Protected;
