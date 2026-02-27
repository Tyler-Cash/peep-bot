import {createSlice} from '@reduxjs/toolkit';

const initialState = {
    isAuthenticated: false,
    isAdmin: false,
    username: null,
};

const authSlice = createSlice({
    name: 'auth',
    initialState,
    reducers: {
        loginSuccess(state, action) {
            state.isAuthenticated = true;
            state.isAdmin = action.payload?.admin ?? false;
            state.username = action.payload?.username ?? null;
        },
        logout(state) {
            state.isAuthenticated = false;
            state.isAdmin = false;
            state.username = null;
        },
    },
});

export const {loginSuccess, logout} = authSlice.actions;
export default authSlice.reducer;
