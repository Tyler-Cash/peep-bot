import React from 'react';
import {Link} from "react-router-dom";
import {useSelector} from "react-redux";
import PropTypes from "prop-types";
import './css/events.css';

Navbar.propTypes = {
    focus: PropTypes.string
};

export default function Navbar({focus}) {
    const isAuthenticated = useSelector(state => state.auth.isAuthenticated);

    return (
        <nav className="navbar navbar-expand peep-navbar">
            <div className="container-fluid">
                <Link className="navbar-brand" to="/event/list">
                    <span className="brand-logo-glow">
                        <img
                            src="https://cdn.frankerfacez.com/emoticon/728261/animated/4"
                            alt="Logo"
                            width="58"
                            height="38"
                            className="d-inline-block"
                        />
                    </span>
                    <span className="brand-text"><span className="brand-peep">Peep</span> <span className="brand-bot">Bot</span></span>
                </Link>
                <button className="navbar-toggler" type="button" data-bs-toggle="collapse"
                        data-bs-target="#navbarNavDropdown" aria-controls="navbarNavDropdown"
                        aria-expanded="false" aria-label="Toggle navigation">
                    <span className="navbar-toggler-icon"></span>
                </button>
                <div className="collapse navbar-collapse" id="navbarNavDropdown">
                    {isAuthenticated ? (
                        <div className="navbar-breadcrumb">
                            <Link className={"nav-breadcrumb-link" + (focus === "LIST" ? " active" : "")}
                                  to="/event/list">Events</Link>
                            {focus === "CREATE" && (
                                <>
                                    <span className="nav-breadcrumb-sep">/</span>
                                    <span className="nav-breadcrumb-current">Create</span>
                                </>
                            )}
                            {focus === "EDIT" && (
                                <>
                                    <span className="nav-breadcrumb-sep">/</span>
                                    <span className="nav-breadcrumb-current">Edit</span>
                                </>
                            )}
                        </div>
                    ) : (<div/>)}
                </div>
            </div>
        </nav>
    )
}
