import {combineReducers} from "redux";
import authReducer from "./authReducer";
import {eventBotApi} from "../api/eventBotApi";

const rootReducer = combineReducers({
    auth: authReducer,
    eventBot: eventBotApi.reducer,
});

export default rootReducer;