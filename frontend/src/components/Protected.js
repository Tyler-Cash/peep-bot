import React from 'react';
import {Navigate,} from 'react-router-dom';
import {useDispatch, useSelector} from "react-redux";
import {useIsLoggedInQuery} from "../api/eventBotApi";
import {loginSuccess, logout} from "../reducers/authReducer";

const Protected = ({children}) => {
    const isAuthenticated = useSelector(state => state.auth.isAuthenticated);
    const dispatch = useDispatch();
    const {data, error, isSuccess} = useIsLoggedInQuery()
    if (isSuccess) {
        dispatch(loginSuccess());
    } else if (error) {
        console.log(error);
        dispatch(logout());
    }
    return isAuthenticated ? children : (<Navigate to="/login"/>);
};

export default Protected;