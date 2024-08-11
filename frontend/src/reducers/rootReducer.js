import {combineReducers} from "redux";
import authReducer from "./authReducer";
import navbarReducer from "./navbarReducer";
import {eventBotApi} from "../api/eventBotApi";

const rootReducer = combineReducers({
    auth: authReducer,
    eventBot: eventBotApi.reducer,
    nav: navbarReducer,
});

export default rootReducer;