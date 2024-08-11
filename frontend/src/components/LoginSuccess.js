import {Navigate} from "react-router-dom";
import {useEffect} from "react";
import {loginSuccess} from "../reducers/authReducer";
import {useDispatch} from "react-redux";

export default function LoginSuccess() {
    const dispatch = useDispatch();

    useEffect(() => {
        dispatch(loginSuccess());
    }, []);

    return (
        <Navigate to={"/"}/>
    );
}