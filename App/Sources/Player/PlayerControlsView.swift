import UIKit

/// Barra superior (título/fechar) e inferior (play, tempo, slider).
/// Sem lógica de reprodução: só emite intenções via closures.
final class PlayerControlsView: UIView {

    var onPlayPause: (() -> Void)?
    var onClose: (() -> Void)?
    /// `finished == false` durante o arrasto (scrub ao vivo), `true` ao soltar.
    var onScrub: ((Double, Bool) -> Void)?

    private(set) var isVisible = true

    var title: String = "" {
        didSet { titleLabel.text = title }
    }

    private let topBar = UIView()
    private let bottomBar = UIView()
    private let topGradient = CAGradientLayer()
    private let bottomGradient = CAGradientLayer()

    private let titleLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let playButton = UIButton(type: .system)
    private let elapsedLabel = UILabel()
    private let remainingLabel = UILabel()
    private let slider = UISlider()

    private var duration: Double = 0
    private var isUserScrubbing = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        setup()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) não usado") }

    private func setup() {
        // A view inteira é transparente a toques; só os controles recebem.
        // Sem isso ela engoliria os gestos do player atrás dela.
        isUserInteractionEnabled = true
        backgroundColor = .clear

        setupTopBar()
        setupBottomBar()
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        guard isVisible else { return false }
        // Só intercepta o toque se ele caiu numa das barras.
        return topBar.frame.contains(point) || bottomBar.frame.contains(point)
    }

    private func setupTopBar() {
        topBar.translatesAutoresizingMaskIntoConstraints = false
        addSubview(topBar)

        topGradient.colors = [UIColor.black.withAlphaComponent(0.75).cgColor,
                              UIColor.clear.cgColor]
        topBar.layer.insertSublayer(topGradient, at: 0)

        closeButton.setImage(UIImage(systemName: "chevron.down"), for: .normal)
        closeButton.tintColor = .white
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 15, weight: .medium)
        titleLabel.lineBreakMode = .byTruncatingMiddle
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        topBar.addSubview(closeButton)
        topBar.addSubview(titleLabel)

        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: topAnchor),
            topBar.leadingAnchor.constraint(equalTo: leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: trailingAnchor),
            topBar.heightAnchor.constraint(equalToConstant: 88),

            closeButton.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor, constant: 16),
            closeButton.bottomAnchor.constraint(equalTo: topBar.bottomAnchor, constant: -16),
            closeButton.widthAnchor.constraint(equalToConstant: 44),
            closeButton.heightAnchor.constraint(equalToConstant: 44),

            titleLabel.leadingAnchor.constraint(equalTo: closeButton.trailingAnchor, constant: 8),
            titleLabel.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor, constant: -16),
            titleLabel.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor),
        ])
    }

    private func setupBottomBar() {
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        addSubview(bottomBar)

        bottomGradient.colors = [UIColor.clear.cgColor,
                                 UIColor.black.withAlphaComponent(0.8).cgColor]
        bottomBar.layer.insertSublayer(bottomGradient, at: 0)

        playButton.setImage(UIImage(systemName: "play.fill"), for: .normal)
        playButton.tintColor = .white
        playButton.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .semibold)
        playButton.addTarget(self, action: #selector(playTapped), for: .touchUpInside)
        playButton.translatesAutoresizingMaskIntoConstraints = false

        [elapsedLabel, remainingLabel].forEach {
            $0.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
            $0.textColor = .white
            $0.text = "--:--"
            $0.translatesAutoresizingMaskIntoConstraints = false
        }

        slider.minimumTrackTintColor = .white
        slider.maximumTrackTintColor = UIColor.white.withAlphaComponent(0.3)
        slider.setThumbImage(Self.thumbImage(diameter: 12), for: .normal)
        slider.setThumbImage(Self.thumbImage(diameter: 18), for: .highlighted)
        slider.translatesAutoresizingMaskIntoConstraints = false
        slider.addTarget(self, action: #selector(sliderChanged), for: .valueChanged)
        slider.addTarget(self, action: #selector(sliderTouchDown), for: .touchDown)
        slider.addTarget(self, action: #selector(sliderTouchUp),
                         for: [.touchUpInside, .touchUpOutside, .touchCancel])

        [playButton, elapsedLabel, slider, remainingLabel].forEach(bottomBar.addSubview)

        NSLayoutConstraint.activate([
            bottomBar.bottomAnchor.constraint(equalTo: bottomAnchor),
            bottomBar.leadingAnchor.constraint(equalTo: leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: trailingAnchor),
            bottomBar.heightAnchor.constraint(equalToConstant: 110),

            playButton.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor, constant: 16),
            playButton.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor, constant: -14),
            playButton.widthAnchor.constraint(equalToConstant: 44),
            playButton.heightAnchor.constraint(equalToConstant: 44),

            elapsedLabel.leadingAnchor.constraint(equalTo: playButton.trailingAnchor, constant: 8),
            elapsedLabel.centerYAnchor.constraint(equalTo: playButton.centerYAnchor),

            remainingLabel.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor, constant: -16),
            remainingLabel.centerYAnchor.constraint(equalTo: playButton.centerYAnchor),

            slider.leadingAnchor.constraint(equalTo: elapsedLabel.trailingAnchor, constant: 10),
            slider.trailingAnchor.constraint(equalTo: remainingLabel.leadingAnchor, constant: -10),
            slider.centerYAnchor.constraint(equalTo: playButton.centerYAnchor),
        ])
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        topGradient.frame = topBar.bounds
        bottomGradient.frame = bottomBar.bounds
    }

    private static func thumbImage(diameter: CGFloat) -> UIImage {
        let size = CGSize(width: diameter, height: diameter)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.white.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(origin: .zero, size: size))
        }
    }

    // MARK: - Atualizações

    func update(currentTime: Double, duration: Double) {
        self.duration = duration
        guard !isUserScrubbing else { return }

        elapsedLabel.text = TimeFormat.clock(currentTime)
        remainingLabel.text = duration > 0 ? "−" + TimeFormat.clock(duration - currentTime) : "--:--"
        slider.value = duration > 0 ? Float(currentTime / duration) : 0
    }

    func apply(state: PlaybackState) {
        let name = (state == .playing) ? "pause.fill" : "play.fill"
        playButton.setImage(UIImage(systemName: name), for: .normal)
        if state == .paused || state == .ended { setVisible(true, animated: true) }
    }

    func setVisible(_ visible: Bool, animated: Bool) {
        guard visible != isVisible else { return }
        isVisible = visible
        let work = { self.topBar.alpha = visible ? 1 : 0; self.bottomBar.alpha = visible ? 1 : 0 }
        animated ? UIView.animate(withDuration: 0.22, animations: work) : work()
    }

    // MARK: - Ações

    @objc private func closeTapped() { onClose?() }
    @objc private func playTapped() { onPlayPause?() }
    @objc private func sliderTouchDown() { isUserScrubbing = true }

    @objc private func sliderChanged() {
        guard duration > 0 else { return }
        let time = Double(slider.value) * duration
        elapsedLabel.text = TimeFormat.clock(time)
        remainingLabel.text = "−" + TimeFormat.clock(duration - time)
        onScrub?(time, false)
    }

    @objc private func sliderTouchUp() {
        isUserScrubbing = false
        guard duration > 0 else { return }
        onScrub?(Double(slider.value) * duration, true)
    }
}
