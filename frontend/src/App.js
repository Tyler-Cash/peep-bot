import './App.css';
import Event from "./components/forms/Event/Event";


function App() {
    return (
        <div>
            <nav className="navbar bg-body-tertiary">
                <div className="container-fluid">
                    <a className="navbar-brand" href="#">
                        <img src="https://cdn.frankerfacez.com/emoticon/728261/animated/4" alt="Logo" width="60"
                             height="40"
                             className="d-inline-block align-text-top"/>
                        Event Bot
                    </a>
                </div>
            </nav>
            <div class="container text-center">
                <Event/>
            </div>
        </div>
    );
}

export default App;
