import SwiftUI
import UniformTypeIdentifiers

/// Ponte para o seletor de arquivos do iOS.
///
/// É por aqui que o pendrive USB entra: o app não pode listar volumes externos
/// sozinho — o iOS não expõe isso. O usuário escolhe a pasta no picker e nós
/// ganhamos acesso com escopo de segurança àquela árvore, que depois
/// persistimos como bookmark em `BookmarkStore`.
struct DocumentPicker: UIViewControllerRepresentable {

    enum Mode {
        /// Escolher uma pasta inteira (pendrive, "Na Minha Máquina", iCloud).
        case folder
        /// Escolher arquivos de vídeo soltos.
        case videos
    }

    let mode: Mode
    let onPick: ([URL]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let types: [UTType] = mode == .folder ? [.folder] : [.movie, .video, .audiovisualContent]
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false)
        picker.allowsMultipleSelection = (mode == .videos)
        picker.shouldShowFileExtensions = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: ([URL]) -> Void
        init(onPick: @escaping ([URL]) -> Void) { self.onPick = onPick }

        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            onPick(urls)
        }
    }
}
